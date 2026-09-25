package com.cobbleraids.spawn;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * Standalone wild-raid scheduler. It does not register raid bosses into Cobblemon's ordinary
 * spawn pools. Phase 31 first selects a configurable rarity tier and then selects a species.
 */
public final class RaidSpawnScheduler {
    private static final ActiveRaidSpawnTracker TRACKER = new ActiveRaidSpawnTracker();
    private static final RaidSpawnCooldowns COOLDOWNS = new RaidSpawnCooldowns();
    private static long schedulerTick;
    private static boolean spawningTrackedBoss;

    private RaidSpawnScheduler() {}

    public static void tick(MinecraftServer server) {
        schedulerTick++;
        if ((schedulerTick % 20L) == 0L) maintainTrackedBosses(server);

        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();
        if (!config.enabled()) return;
        if ((schedulerTick % config.checkIntervalTicks()) != 0L) return;
        if (ThreadLocalRandom.current().nextDouble() > config.spawnAttemptChance()) return;

        purgeRemoved(server);
        if (TRACKER.size() >= config.maxActiveRaids()) return;

        List<ServerPlayer> candidates = new ArrayList<>(server.getPlayerList().getPlayers());
        candidates.removeIf(RaidSpawnScheduler::shouldSkipPlayer);
        Collections.shuffle(candidates);

        int attempts = Math.min(config.attemptsPerCheck(), candidates.size());
        for (int i = 0; i < attempts && TRACKER.size() < config.maxActiveRaids(); i++) {
            attemptForPlayer(candidates.get(i), config);
        }
    }

    private static boolean shouldSkipPlayer(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return true;
        return Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null;
    }

    private static void attemptForPlayer(ServerPlayer player, CobbleRaidsConfig.NaturalSpawning config) {
        ServerLevel level = (ServerLevel) player.level();
        ResourceLocation dimensionId = level.dimension().location();
        String playerName = player.getGameProfile().getName();

        if (!ShowdownIntegrationInstaller.isReady()) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId,
                    RaidSpawnHistory.Outcome.SHOWDOWN_INTEGRATION_FAILED,
                    "CobbleRaids' Showdown edits are not installed; see the server log at startup");
            return;
        }

        int activeHere = trackedInDimension(dimensionId);
        if (activeHere >= config.maxActiveRaidsPerDimension()) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.PER_DIMENSION_CAP,
                    activeHere + "/" + config.maxActiveRaidsPerDimension() + " active in dimension");
            return;
        }

        Optional<BlockPos> position = RaidSpawnPositionFinder.findLand(level, player, config);
        if (position.isEmpty()) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.NO_VALID_TERRAIN,
                    "no valid surface position found in " + config.locationAttempts() + " attempts");
            return;
        }
        BlockPos pos = position.get();
        if (tooCloseToAnotherRaid(level, pos, config.minDistanceBetweenRaids())) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.TOO_CLOSE_TO_EXISTING,
                    "candidate " + pos.toShortString() + " within " + config.minDistanceBetweenRaids()
                            + " blocks of an active raid");
            return;
        }

        Holder<Biome> biomeHolder = level.getBiome(pos);
        ResourceLocation biomeId = biomeHolder.unwrapKey().map(key -> key.location()).orElse(null);
        RaidSpawnContext context = new RaidSpawnContext(dimensionId, biomeId, biomeHolder, level.getDayTime());

        List<RaidDefinition> eligible = RaidDefinitionRegistry.all().stream()
                .filter(context::matches)
                .filter(RaidSpawnScheduler::offCooldown)
                .filter(RaidSpawnScheduler::belowDefinitionCap)
                .toList();
        if (eligible.isEmpty()) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.NO_ELIGIBLE_DEFINITIONS,
                    "no definition matched biome=" + biomeId + " and was off cooldown/below its concurrent cap");
            return;
        }

        RaidDefinition selected = RaidTierSelector.select(
                eligible,
                RaidDefinition::rarityTier,
                definition -> definition.spawn().weight(),
                config.tierWeights(),
                ThreadLocalRandom.current()
        );
        if (selected == null) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.TIER_SELECTION_FAILED,
                    eligible.size() + " eligible definition(s) but tier selection returned none");
            return;
        }

        // Deliberately after tier selection and deliberately terminal: a failed roll means no raid
        // this attempt, never a re-roll into a different tier. Re-rolling would just redistribute
        // the spawn, which is exactly the behaviour tier_weights already has and the reason it
        // cannot be used to make one tier rarer -- see RaidTierSpawnChance.
        double tierChance = config.tierSpawnChance().chanceFor(selected.rarityTier());
        if (ThreadLocalRandom.current().nextDouble() >= tierChance) {
            RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.TIER_CHANCE_SKIPPED,
                    selected.rarityTier().serializedName() + " selected but skipped by tier_spawn_chance "
                            + CommandFormat.percent(tierChance * 100.0));
            return;
        }

        spawnTracked(level, pos, biomeId, dimensionId, selected);
        RaidSpawnHistory.record(schedulerTick, playerName, dimensionId, RaidSpawnHistory.Outcome.SUCCESS,
                CommandFormat.shortId(selected.id()) + " (" + selected.rarityTier().serializedName() + ")");
    }

    static PokemonEntity spawnTracked(
            ServerLevel level,
            BlockPos pos,
            ResourceLocation biomeId,
            ResourceLocation dimensionId,
            RaidDefinition selected
    ) {
        PokemonEntity entity;
        // onNaturalBossLoaded fires while the entity joins the level, before it can be marked and
        // tracked here. Suppress the orphan sweep for the duration of our own spawn.
        spawningTrackedBoss = true;
        try {
            entity = RaidBossSpawner.spawnAt(level, Vec3.atBottomCenterOf(pos), selected);
        } finally {
            spawningTrackedBoss = false;
        }
        RaidBossEntityMarker.markNatural(entity);
        TRACKER.track(
                entity.getUUID(),
                selected.id(),
                dimensionId,
                pos,
                schedulerTick,
                selected.spawn().despawnSeconds(),
                selected.spawn().maxLifetimeSeconds()
        );
        COOLDOWNS.start(selected.id(), schedulerTick, selected.spawn().cooldownSeconds());

        RaidSpawnAnnouncementService.naturalSpawn(
                level.getServer(), entity, biomeId, dimensionId, pos, selected.rarityTier());
        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Natural raid spawned: " + selected.id() + " at " + pos
                    + " in " + dimensionId + " biome=" + biomeId + " tier="
                    + selected.rarityTier().serializedName() + " active=" + TRACKER.size());
        }
        return entity;
    }

    static boolean offCooldown(RaidDefinition definition) {
        return COOLDOWNS.isOffCooldown(definition.id(), schedulerTick);
    }

    static boolean belowDefinitionCap(RaidDefinition definition) {
        return TRACKER.belowDefinitionCap(definition.id(), definition.spawn().maxConcurrent());
    }

    static int trackedInDimension(ResourceLocation dimension) {
        return TRACKER.countInDimension(dimension);
    }

    static int trackedCount() { return TRACKER.size(); }

    /** Every natural boss holding a raid slot, and the dimension it was spawned in. For auditing. */
    public static Map<UUID, ResourceLocation> trackedBosses() {
        Map<UUID, ResourceLocation> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, TrackedRaidSpawn> entry : TRACKER.snapshot()) {
            snapshot.put(entry.getKey(), entry.getValue().dimension());
        }
        return snapshot;
    }

    static boolean tooCloseToAnotherRaid(ServerLevel level, BlockPos pos, double minimumDistance) {
        return TRACKER.anyWithin(level.dimension().location(), pos, minimumDistance);
    }

    private static void maintainTrackedBosses(MinecraftServer server) {
        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();
        // Allocated once a second, not once a tick: maintain() is called from the 20-tick branch.
        TRACKER.maintain(schedulerTick, new WorldMaintenance(server, config.despawnPlayerRadius()));
    }

    /**
     * The world half of a maintenance pass. The tracker decides what should happen to each boss and
     * this performs it, which is the split that lets the decision rules be tested without a server.
     */
    private record WorldMaintenance(MinecraftServer server, double despawnRadius)
            implements ActiveRaidSpawnTracker.Maintenance<PokemonEntity> {

        @Override
        public PokemonEntity resolve(UUID bossId, TrackedRaidSpawn spawn) {
            return resolveBoss(server, bossId, spawn);
        }

        @Override
        public ActiveRaidSpawnTracker.Presence presence(PokemonEntity boss) {
            if (boss.isRemoved()) return new ActiveRaidSpawnTracker.Presence(true, false, false, false);
            boolean battling = boss.isBattling();
            // Short-circuited deliberately, exactly as the original loop was: a battling or lobbied
            // boss is kept alive regardless, and hasNearbyPlayer walks the whole player list.
            boolean lobbyActive = !battling && RaidLobbyManager.hasActiveLobby(boss);
            boolean playerNearby = !battling && !lobbyActive
                    && RaidBossLookup.hasNearbyPlayer(server, boss, despawnRadius);
            return new ActiveRaidSpawnTracker.Presence(false, battling, lobbyActive, playerNearby);
        }

        @Override
        public void onLifetimeExpired(UUID bossId, TrackedRaidSpawn spawn, PokemonEntity boss) {
            if (boss != null) {
                RaidLobbyManager.cancelForBoss(boss);
                RaidSpawnAnnouncementService.bossLeft(boss, despawnRadius);
                boss.discard();
            }
            if (CobbleRaidsConfigManager.get().debugLogging()) {
                RaidLog.info("Wild raid " + spawn.definitionId()
                        + " hit its " + spawn.maxLifetimeSeconds() + "s lifetime cap"
                        + (boss == null ? " (deferred: chunk not loaded)" : ""));
            }
        }

        @Override
        public void onIdleDespawn(UUID bossId, TrackedRaidSpawn spawn, PokemonEntity boss) {
            if (CobbleRaidsConfigManager.get().debugLogging()) {
                RaidLog.info("Despawning unattended wild raid " + spawn.definitionId()
                        + (boss == null ? " (deferred: chunk not loaded)" : ""));
            }
            if (boss != null) boss.discard();
        }

        @Override
        public void onExpiryWarning(UUID bossId, TrackedRaidSpawn spawn, PokemonEntity boss, long secondsLeft) {
            RaidSpawnAnnouncementService.expiryWarning(boss, despawnRadius, secondsLeft);
        }
    }

    /** Seconds of lifetime remaining, or -1 for a boss this scheduler does not track (admin-spawned). */
    public static long secondsUntilExpiry(UUID bossId) {
        return TRACKER.secondsUntilExpiry(bossId, schedulerTick);
    }

    private static PokemonEntity resolveBoss(MinecraftServer server, UUID bossId, TrackedRaidSpawn spawn) {
        return RaidBossLookup.resolve(server, bossId, spawn.dimension());
    }

    /**
     * Enforces the Phase 32 invariant: a natural raid boss that is untracked by ActiveRaidSpawnTracker should not
     * exist. Bound to ServerEntityEvents.ENTITY_LOAD, this is what actually removes bosses whose
     * despawn timer expired while their chunk was unloaded, bosses orphaned by an earlier session,
     * and anything the SERVER_STARTED sweep could not see because its chunk was not loaded yet.
     * Administrator-placed bosses are untagged as natural and are deliberately left alone.
     */
    public static void onNaturalBossLoaded(Entity entity, ServerLevel level) {
        if (spawningTrackedBoss) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!RaidBossEntityMarker.isNatural(pokemon) || !RaidBossEntityMarker.isRaidBoss(pokemon)) return;
        if (TRACKER.isTracked(pokemon.getUUID())) return;
        if (pokemon.isBattling() || RaidLobbyManager.hasActiveLobby(pokemon)) return;

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Removed untracked natural raid boss " + pokemon.getUUID()
                    + " on load in " + level.dimension().location());
        }
        pokemon.discard();
    }

    /**
     * Frees a tracked boss's raid slot the moment the entity is genuinely destroyed, whatever
     * destroyed it: raid finalization, an admin despawn, /kill, the void, or another mod.
     *
     * <p>This exists because the scheduler cannot detect the destruction on its own. Its
     * maintenance pass looks for an entry that still resolves and reports isRemoved(), but
     * Entity.discard() unhooks the entity from ServerLevel's UUID lookup synchronously --
     * setRemoved -> PersistentEntitySectionManager.stopTracking -> EntityLookup.remove drops
     * byUuid before discard() even returns -- while the pass only runs once a second. By then the
     * boss no longer resolves at all, which is deliberately read as "chunk not loaded, keep
     * waiting". The finished raid therefore held its slot against max_active_raids (and blocked
     * min_distance_between_raids) for the remainder of its despawn_seconds.
     *
     * <p>RemovalReason.shouldDestroy() is exactly the distinction the Phase 32 invariant needs:
     * true only for KILLED and DISCARDED, false for UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER and
     * CHANGED_DIMENSION. An unloading boss is left tracked, as before.
     *
     * <p>Bound to ServerEntityEvents.ENTITY_UNLOAD, which fires for every entity leaving every
     * loaded chunk, so the body is ordered cheapest-test-first and allocates nothing. The reason
     * check is deliberately ahead of the instanceof: ordinary chunk unload is the overwhelming
     * majority of this traffic and shouldDestroy() rejects all of it with one field read.
     */
    public static void onEntityUnloaded(Entity entity, ServerLevel level) {
        if (TRACKER.isEmpty()) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        // Null happens when a section merely stops being accessible without the entity being
        // removed at all; that is an unload, not a destruction.
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!TRACKER.forget(pokemon.getUUID())) return;

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Released raid slot for destroyed boss " + pokemon.getUUID()
                    + " (" + reason + ") in " + level.dimension().location() + ", active=" + TRACKER.size());
        }
    }



    /**
     * Drops only bosses that are provably gone. An entry whose boss does not resolve is kept,
     * because "not loaded" and "no longer exists" are indistinguishable from a lookup alone and
     * treating the first as the second is what leaked untracked bosses before Phase 32.
     *
     * <p>Same fallback role as the equivalent check in maintainTrackedBosses: onEntityUnloaded is
     * what releases a slot in practice. Kept because it is bounded by max_active_raids entries and
     * runs only on a spawn attempt.
     */
    static void purgeRemoved(MinecraftServer server) {
        TRACKER.releaseProvablyGone((bossId, spawn) -> {
            PokemonEntity boss = resolveBoss(server, bossId, spawn);
            return boss != null && boss.isRemoved();
        });
    }

    /**
     * A dimension-managing mod (e.g. Multiworld) can close a ServerLevel outright, not just unload
     * its chunks. When that happens any tracked boss there can never resolve again, and without this
     * it would otherwise sit in the tracker for its full despawn_seconds -- occupying a max_active_raids
     * slot for a raid nobody can ever reach. Server shutdown also fires this for every level, which
     * is harmless: onServerStopping already clears the tracker around the same time.
     */
    public static void onLevelUnloaded(MinecraftServer server, ServerLevel level) {
        ResourceLocation dimensionId = level.dimension().location();
        boolean removedAny = TRACKER.forgetDimension(dimensionId);
        if (removedAny && CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Forgot natural raid boss(es) tracked in closed dimension " + dimensionId);
        }
    }

    /**
     * Removes every marked raid boss still standing from a prior server session -- natural, owned,
     * or admin-spawned alike.
     *
     * <p>RaidRegistry and RaidLobbyManager are both empty at this point (SERVER_STOPPED already
     * cleared them, or the old JVM never got that far), so nothing here is legitimately in a battle
     * or a lobby: every marked boss this finds is a leftover. This used to discard only natural and
     * owned bosses -- on the theory that a raid still in battle at shutdown would already have been
     * ended by the disconnect its own players caused -- but {@link RaidLifecycleCoordinator#abortAll}
     * now owns that job directly on SERVER_STOPPING, ahead of the world save, so this is purely the
     * fallback for whatever skips a graceful stop (a crash, a hard kill). An admin-spawned boss is
     * neither natural nor owned and fell through both checks, which is exactly how one survived a
     * `/stop` mid-fight, reloaded permanently invulnerable and un-ownable, and was mistaken for a
     * second copy of the boss it was fought alongside.
     *
     * <p>An owned encounter specifically can never resume even when its boss is caught here fresh:
     * its battle and its listener died with the old server, so its owner has to rebuild it regardless.
     */
    public static void onServerStarted(MinecraftServer server) {
        TRACKER.clear();
        COOLDOWNS.clear();
        schedulerTick = 0L;
        int purged = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof PokemonEntity pokemon) || !RaidBossEntityMarker.isRaidBoss(pokemon)) continue;
                pokemon.discard();
                purged++;
            }
        }
        if (purged > 0) {
            RaidLog.info("Removed " + purged
                    + " stale raid boss(es) from a prior server session.");
        }
    }

    /** Prevents persistent natural bosses from becoming orphaned on clean shutdown. */
    public static void onServerStopping(MinecraftServer server) {
        for (Map.Entry<UUID, TrackedRaidSpawn> entry : TRACKER.snapshot()) {
            PokemonEntity boss = resolveBoss(server, entry.getKey(), entry.getValue());
            // Bosses in unloaded chunks are left to onNaturalBossLoaded on the next session.
            if (boss != null && !boss.isRemoved()) boss.discard();
        }
        TRACKER.clear();
        COOLDOWNS.clear();
        schedulerTick = 0L;
    }

    public static int activeCount(MinecraftServer server) {
        purgeRemoved(server);
        return TRACKER.size();
    }

    /**
     * Drops a boss from tracking immediately. Admin despawns discard the entity directly rather
     * than going through maintainTrackedBosses, so without this the entry would otherwise sit in
     * the tracker for up to its full despawn_seconds: still counted against max_active_raids and
     * max_concurrent, and still blocking nearby spawns via min_distance_between_raids, since both
     * checks read the tracker regardless of whether the tracked boss still resolves. A no-op for a UUID
     * that was never natural (e.g. an admin-spawned boss), since Map.remove on a missing key is safe.
     */
    public static void forget(UUID bossId) {
        TRACKER.forget(bossId);
    }

    /** Clears one definition's natural-spawn cooldown early. Returns false if it wasn't on cooldown. */
    public static boolean resetCooldown(ResourceLocation definitionId) {
        return COOLDOWNS.reset(definitionId);
    }

    /** Definitions still on natural-spawn cooldown and the seconds left on each, soonest first. */
    public static List<Map.Entry<ResourceLocation, Long>> activeCooldowns() {
        return COOLDOWNS.remaining(schedulerTick);
    }
}
