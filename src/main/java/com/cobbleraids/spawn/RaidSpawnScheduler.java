package com.cobbleraids.spawn;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
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
    private static final Map<UUID, ActiveSpawn> ACTIVE = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Long> NEXT_ALLOWED_TICK = new HashMap<>();
    private static long schedulerTick;
    private static boolean spawningTrackedBoss;

    private RaidSpawnScheduler() {}

    /**
     * Phase 32: tracked by UUID and spawn position rather than by a PokemonEntity reference.
     *
     * Minecraft marks an entity removed with RemovalReason.UNLOADED_TO_CHUNK when its chunk
     * unloads, so a cached reference starts answering isRemoved() == true while the boss is still
     * very much in the world. Holding one made the scheduler drop unattended bosses from tracking
     * the moment a player walked far enough away for the chunk to unload - which is exactly when
     * the despawn timer was supposed to start running. The boss then stayed forever, because
     * RaidBossSpawner marks it setPersistenceRequired(), and its slot against max_active_raids
     * was quietly released.
     */
    private static final class ActiveSpawn {
        final ResourceLocation definitionId;
        final ResourceLocation dimension;
        final BlockPos position;
        final long spawnedAtTick;
        final int despawnSeconds;
        final int maxLifetimeSeconds;
        // Mutated once per second by the maintenance pass on the server thread, which is the only
        // thing that touches ACTIVE. A record here meant allocating a replacement every second for
        // every tracked boss just to advance a timer, and a wither method per mutable field.
        long lastNearbyPlayerTick;
        int expiryWarningsSent;

        ActiveSpawn(ResourceLocation definitionId, ResourceLocation dimension, BlockPos position,
                    long spawnedAtTick, int despawnSeconds, int maxLifetimeSeconds) {
            this.definitionId = definitionId;
            this.dimension = dimension;
            this.position = position;
            this.spawnedAtTick = spawnedAtTick;
            this.lastNearbyPlayerTick = spawnedAtTick;
            this.despawnSeconds = despawnSeconds;
            this.maxLifetimeSeconds = maxLifetimeSeconds;
        }

        ResourceLocation definitionId() { return definitionId; }
        ResourceLocation dimension() { return dimension; }
        BlockPos position() { return position; }

        long secondsLeft(long now) {
            return maxLifetimeSeconds - (now - spawnedAtTick) / 20L;
        }
    }

    public static void tick(MinecraftServer server) {
        schedulerTick++;
        if ((schedulerTick % 20L) == 0L) maintainTrackedBosses(server);

        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();
        if (!config.enabled()) return;
        if ((schedulerTick % config.checkIntervalTicks()) != 0L) return;
        if (ThreadLocalRandom.current().nextDouble() > config.spawnAttemptChance()) return;

        purgeRemoved(server);
        if (ACTIVE.size() >= config.maxActiveRaids()) return;

        List<ServerPlayer> candidates = new ArrayList<>(server.getPlayerList().getPlayers());
        candidates.removeIf(RaidSpawnScheduler::shouldSkipPlayer);
        Collections.shuffle(candidates);

        int attempts = Math.min(config.attemptsPerCheck(), candidates.size());
        for (int i = 0; i < attempts && ACTIVE.size() < config.maxActiveRaids(); i++) {
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

        int activeHere = activeInDimension(dimensionId);
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
        if (isTooCloseToAnotherRaid(level, pos, config.minDistanceBetweenRaids())) {
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

    private static PokemonEntity spawnTracked(
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
        ActiveSpawn tracked = new ActiveSpawn(
                selected.id(),
                dimensionId,
                pos,
                schedulerTick,
                selected.spawn().despawnSeconds(),
                selected.spawn().maxLifetimeSeconds()
        );
        ACTIVE.put(entity.getUUID(), tracked);
        NEXT_ALLOWED_TICK.put(selected.id(), schedulerTick + selected.spawn().cooldownSeconds() * 20L);

        announceNaturalSpawn(level.getServer(), entity, biomeId, dimensionId, pos, selected.rarityTier());
        if (CobbleRaidsConfigManager.get().debugLogging()) {
            System.out.println("[CobbleRaids] Natural raid spawned: " + selected.id() + " at " + pos
                    + " in " + dimensionId + " biome=" + biomeId + " tier="
                    + selected.rarityTier().serializedName() + " active=" + ACTIVE.size());
        }
        return entity;
    }

    private static void announceNaturalSpawn(
            MinecraftServer server,
            PokemonEntity entity,
            ResourceLocation biomeId,
            ResourceLocation dimensionId,
            BlockPos position,
            RaidRarityTier tier
    ) {
        int hintX = coordinateHint(position.getX());
        int hintZ = coordinateHint(position.getZ());
        MutableComponent speciesName = entity.getPokemon().getSpecies().getTranslatedName();
        String biomeName = biomeId == null ? "Unknown Biome" : friendlyName(biomeId);

        MutableComponent message = Component.literal("[CobbleRaids] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("A wild ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(tier.displayName() + " ").withStyle(RaidTierPresentation.color(tier)))
                .append(speciesName.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" raid has appeared in ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(biomeName).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("! Coordinate hint: near X " + hintX + ", Z " + hintZ)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + friendlyName(dimensionId) + ").")
                        .withStyle(ChatFormatting.DARK_GRAY));

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            onlinePlayer.sendSystemMessage(message);
        }
    }

    static int coordinateHint(int coordinate) {
        return coordinate >= 0 ? (coordinate + 50) / 100 * 100 : (coordinate - 50) / 100 * 100;
    }

    static String friendlyName(ResourceLocation id) {
        String[] words = id.getPath().replace('/', ' ').replace('_', ' ').split(" +");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.isEmpty() ? id.toString() : result.toString();
    }

    public static int sendSpawnInfo(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("/cobbleraids spawninfo must be run by a player."));
            return 0;
        }

        purgeRemoved(source.getServer());
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition();
        Holder<Biome> biomeHolder = level.getBiome(pos);
        ResourceLocation biomeId = biomeHolder.unwrapKey().map(key -> key.location()).orElse(null);
        ResourceLocation dimensionId = level.dimension().location();
        RaidSpawnContext context = new RaidSpawnContext(dimensionId, biomeId, biomeHolder, level.getDayTime());
        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();

        List<RaidDefinition> environmental = RaidDefinitionRegistry.all().stream()
                .filter(context::matches)
                .sorted(Comparator.comparing(definition -> definition.species().getPath()))
                .toList();
        List<RaidDefinition> eligible = environmental.stream()
                .filter(RaidSpawnScheduler::offCooldown)
                .filter(RaidSpawnScheduler::belowDefinitionCap)
                .toList();
        Map<RaidRarityTier, Integer> counts = RaidTierSelector.counts(eligible, RaidDefinition::rarityTier);
        Map<RaidRarityTier, Double> odds =
                RaidTierSelector.normalizedPercentages(counts, config.tierWeights(), config.tierSpawnChance());
        double noSpawn = RaidTierSelector.noSpawnPercentage(odds);

        source.sendSuccess(() -> CommandFormat.header("Wild spawn director"), false);
        if (!config.enabled()) {
            // Promoted from a trailing note to its own red line: it makes every number below moot.
            source.sendSuccess(() -> Component.literal(" natural spawning is DISABLED")
                    .withStyle(ChatFormatting.RED), false);
        }
        source.sendSuccess(() -> CommandFormat.row((biomeId == null ? "unknown biome" : CommandFormat.shortId(biomeId))
                + " · " + CommandFormat.shortId(dimensionId)
                + " · " + RaidDefinition.SpawnTime.current(level.getDayTime()).name().toLowerCase(Locale.ROOT)), false);
        source.sendSuccess(() -> CommandFormat.row("active " + ACTIVE.size() + "/" + config.maxActiveRaids()
                + " global · " + activeInDimension(dimensionId) + "/"
                + config.maxActiveRaidsPerDimension() + " here"), false);

        for (RaidRarityTier tier : RaidRarityTier.values()) {
            List<String> names = eligible.stream()
                    .filter(definition -> definition.rarityTier() == tier)
                    .map(definition -> definition.species().getPath())
                    .toList();
            source.sendSuccess(() -> CommandFormat.row(
                            CommandFormat.pad(tier.serializedName(), 11)
                                    + CommandFormat.pad(CommandFormat.percent(odds.getOrDefault(tier, 0.0)), 7)
                                    + CommandFormat.pad(Integer.toString(names.size()), 4)
                                    + CommandFormat.names(names, 3))
                    .withStyle(RaidTierPresentation.color(tier)), false);
        }

        // Only worth a line when tier_spawn_chance is actually holding raids back; at the default
        // 1.0 across the board this is 0 and the odds column sums to 100 as it always did.
        if (noSpawn > 0.05) {
            source.sendSuccess(() -> CommandFormat.row(
                            CommandFormat.pad("no spawn", 11) + CommandFormat.pad(CommandFormat.percent(noSpawn), 7)
                                    + "held back by tier_spawn_chance")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }

        int blocked = environmental.size() - eligible.size();
        source.sendSuccess(() -> CommandFormat.row("eligible " + eligible.size() + "/" + environmental.size()
                + " here" + (blocked == 0 ? "" : " · " + blocked + " on cooldown or at cap")), false);
        return eligible.size();
    }

    public static int testWild(CommandSourceStack source, String rawPokemonName) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("/cobbleraids testwild must be run by a player."));
            return 0;
        }

        RaidDefinition definition = resolveSpecies(source, rawPokemonName);
        if (definition == null) return 0;

        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();
        if (!config.enabled()) {
            source.sendFailure(Component.literal("Natural raid spawning is disabled in the CobbleRaids config."));
            return 0;
        }

        purgeRemoved(source.getServer());
        ServerLevel level = source.getLevel();
        ResourceLocation dimensionId = level.dimension().location();
        if (ACTIVE.size() >= config.maxActiveRaids()
                || activeInDimension(dimensionId) >= config.maxActiveRaidsPerDimension()
                || !belowDefinitionCap(definition)) {
            source.sendFailure(Component.literal("A natural raid cap is full. Despawn an active boss and retry."));
            return 0;
        }

        for (int search = 0; search < 16; search++) {
            Optional<BlockPos> candidate = RaidSpawnPositionFinder.findLand(level, player, config);
            if (candidate.isEmpty()) continue;
            BlockPos pos = candidate.get();
            if (isTooCloseToAnotherRaid(level, pos, config.minDistanceBetweenRaids())) continue;

            Holder<Biome> biomeHolder = level.getBiome(pos);
            ResourceLocation biomeId = biomeHolder.unwrapKey().map(key -> key.location()).orElse(null);
            RaidSpawnContext context = new RaidSpawnContext(
                    dimensionId, biomeId, biomeHolder, level.getDayTime());
            if (!context.matches(definition)) continue;

            try {
                PokemonEntity boss = spawnTracked(level, pos, biomeId, dimensionId, definition);
                source.sendSuccess(() -> Component.literal("Spawned tracked wild "
                        + definition.species().getPath() + " ("
                        + definition.rarityTier().serializedName() + ") at "
                        + CommandFormat.coords(boss.getX(), boss.getY(), boss.getZ()))
                        .withStyle(ChatFormatting.GREEN), true);
                source.sendSuccess(() -> CommandFormat.hint(
                        " chance and cooldown bypassed · wild tracking active"), false);
                return 1;
            } catch (RuntimeException ex) {
                source.sendFailure(Component.literal("Failed to spawn " + definition.species().getPath()
                        + ": " + ex.getMessage()));
                return 0;
            }
        }

        source.sendFailure(Component.literal("No valid nearby natural position for "
                + definition.species().getPath()
                + ". Stand in one of its allowed biomes/times and use /cobbleraids spawninfo, then retry."));
        return 0;
    }

    private static RaidDefinition resolveSpecies(CommandSourceStack source, String rawPokemonName) {
        String pokemonName = rawPokemonName.trim().toLowerCase(Locale.ROOT);
        if (pokemonName.isEmpty() || pokemonName.contains(":")) {
            source.sendFailure(Component.literal(
                    "Use a species name only, for example: /cobbleraids testwild garchomp"));
            return null;
        }

        List<RaidDefinition> matches = RaidDefinitionRegistry.findBySpeciesName(pokemonName);
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(matches.isEmpty()
                    ? "No raid definition uses species '" + pokemonName + "'."
                    : "Multiple raid definitions use species '" + pokemonName + "'."));
            return null;
        }
        return matches.getFirst();
    }

    private static boolean offCooldown(RaidDefinition definition) {
        return schedulerTick >= NEXT_ALLOWED_TICK.getOrDefault(definition.id(), 0L);
    }

    // Used as a filter over every loaded definition (~130 of them) on each spawn attempt, so this
    // counts with a plain loop rather than building a stream pipeline per definition.
    private static boolean belowDefinitionCap(RaidDefinition definition) {
        int max = definition.spawn().maxConcurrent();
        int active = 0;
        for (ActiveSpawn spawn : ACTIVE.values()) {
            if (spawn.definitionId().equals(definition.id()) && ++active >= max) return false;
        }
        return true;
    }

    private static int activeInDimension(ResourceLocation dimension) {
        int count = 0;
        for (ActiveSpawn spawn : ACTIVE.values()) {
            if (spawn.dimension().equals(dimension)) count++;
        }
        return count;
    }

    private static boolean isTooCloseToAnotherRaid(ServerLevel level, BlockPos pos, double minimumDistance) {
        if (minimumDistance <= 0.0) return false;
        ResourceLocation dimensionId = level.dimension().location();
        double maxDistanceSqr = minimumDistance * minimumDistance;
        for (ActiveSpawn spawn : ACTIVE.values()) {
            // Recorded spawn positions keep this check working for bosses whose chunk is unloaded,
            // which a live entity lookup cannot do.
            if (!spawn.dimension().equals(dimensionId)) continue;
            if (spawn.position().distSqr(pos) < maxDistanceSqr) return true;
        }
        return false;
    }

    private static void maintainTrackedBosses(MinecraftServer server) {
        CobbleRaidsConfig.NaturalSpawning config = CobbleRaidsConfigManager.get().naturalSpawning();
        // Walk a snapshot, not ACTIVE itself. boss.discard() below fires
        // ServerEntityEvents.ENTITY_UNLOAD synchronously on this thread, and onEntityUnloaded
        // services it by removing that boss's entry from ACTIVE. Iterating the live map meant that
        // re-entrant removal bumped the map's modCount mid-pass, and the next structural change
        // from this loop -- an iterator.remove() a few lines on, or the following iterator.next()
        // -- threw ConcurrentModificationException straight into the server tick. This pass now
        // makes its own removals directly on ACTIVE; the copy is cheap, bounded by max_active_raids.
        for (Map.Entry<UUID, ActiveSpawn> entry : List.copyOf(ACTIVE.entrySet())) {
            UUID bossId = entry.getKey();
            ActiveSpawn active = entry.getValue();
            // Already released by onEntityUnloaded during an earlier discard() in this same pass.
            if (!ACTIVE.containsKey(bossId)) continue;
            PokemonEntity boss = resolveBoss(server, bossId, active);

            // Fallback only. onEntityUnloaded already released the slot for anything destroyed in a
            // loaded chunk, and it runs synchronously inside discard(). This still catches the one
            // case it cannot see: an entity destroyed while its section is inaccessible skips
            // PersistentEntitySectionManager.stopTracking, so no unload event fires and the entry
            // stays resolvable while reporting removed. An ordinary unloaded boss does not resolve
            // at all and is handled below.
            if (boss != null && boss.isRemoved()) {
                ACTIVE.remove(bossId);
                continue;
            }

            // A raid that is actually being fought always finishes. Everything below is about bosses
            // nobody is fighting, and finalization discards the entity itself when the battle ends.
            boolean inBattle = boss != null && boss.isBattling();

            // Total lifetime cap, checked before the idle timer because it is the only rule a player
            // cannot reset. despawn_seconds measures unattended time and restarts every second
            // somebody stands in range, so on its own a camped boss lives forever, holding a
            // max_active_raids slot and blocking min_distance_between_raids for everyone else.
            // A recruiting lobby deliberately does NOT extend this, or re-opening one would be an
            // unlimited refresh; RaidLobbyManager refuses to open one near expiry instead.
            if (!inBattle && active.secondsLeft(schedulerTick) <= 0L) {
                if (boss != null) {
                    RaidLobbyManager.cancelForBoss(boss);
                    announceExpiry(server, boss, config.despawnPlayerRadius());
                    boss.discard();
                }
                if (CobbleRaidsConfigManager.get().debugLogging()) {
                    System.out.println("[CobbleRaids] Wild raid " + active.definitionId()
                            + " hit its " + active.maxLifetimeSeconds + "s lifetime cap"
                            + (boss == null ? " (deferred: chunk not loaded)" : ""));
                }
                ACTIVE.remove(bossId);
                continue;
            }

            if (boss != null) {
                // Recruitment and combat own the boss lifecycle while either is active.
                if (inBattle || RaidLobbyManager.hasActiveLobby(boss)) {
                    active.lastNearbyPlayerTick = schedulerTick;
                    continue;
                }
                if (hasNearbyPlayer(server, boss, config.despawnPlayerRadius())) {
                    active.lastNearbyPlayerTick = schedulerTick;
                    // Only somebody standing here can see the boss, so this is the only case where a
                    // fading warning has an audience worth sending it to.
                    warnBeforeExpiry(server, boss, active, config.despawnPlayerRadius());
                    continue;
                }
            }
            // boss == null means the chunk holding it is not loaded, which is itself proof that no
            // player is near it. Idle time keeps accruing rather than the entry being dropped.

            long idleTicks = schedulerTick - active.lastNearbyPlayerTick;
            if (idleTicks < active.despawnSeconds * 20L) continue;

            if (CobbleRaidsConfigManager.get().debugLogging()) {
                System.out.println("[CobbleRaids] Despawning unattended wild raid " + active.definitionId()
                        + (boss == null ? " (deferred: chunk not loaded)" : ""));
            }
            if (boss != null) boss.discard();
            // An unloaded boss cannot be discarded from here. Dropping it from ACTIVE hands it to
            // onNaturalBossLoaded, which removes any untracked natural boss the moment it loads.
            ACTIVE.remove(bossId);
        }
    }

    /** Seconds of lifetime remaining, or -1 for a boss this scheduler does not track (admin-spawned). */
    public static long secondsUntilExpiry(UUID bossId) {
        ActiveSpawn active = ACTIVE.get(bossId);
        return active == null ? -1L : Math.max(0L, active.secondsLeft(schedulerTick));
    }

    /**
     * One warning at each threshold, tracked on the entry so a player standing there for the whole
     * countdown is told twice rather than once a second.
     */
    private static void warnBeforeExpiry(MinecraftServer server, PokemonEntity boss, ActiveSpawn active, double radius) {
        long secondsLeft = active.secondsLeft(schedulerTick);
        int stage = secondsLeft <= 10L ? 2 : secondsLeft <= 60L ? 1 : 0;
        if (stage == 0 || stage <= active.expiryWarningsSent) return;
        active.expiryWarningsSent = stage;
        broadcastNear(boss, radius, Component.literal("This raid boss will leave in ~"
                        + Math.max(1L, secondsLeft) + "s.")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void announceExpiry(MinecraftServer server, PokemonEntity boss, double radius) {
        broadcastNear(boss, radius, Component.literal("The raid boss lost interest and left.")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Level-local player list rather than the whole server's: only players in this dimension can
     * possibly be in range, and on a busy server that is a much shorter list to walk.
     */
    private static void broadcastNear(PokemonEntity boss, double radius, Component message) {
        if (!(boss.level() instanceof ServerLevel level)) return;
        double radiusSqr = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(boss) <= radiusSqr) player.sendSystemMessage(message);
        }
    }

    private static PokemonEntity resolveBoss(MinecraftServer server, UUID bossId, ActiveSpawn active) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, active.dimension()));
        if (level == null) return null;
        return level.getEntity(bossId) instanceof PokemonEntity pokemon ? pokemon : null;
    }

    /**
     * Enforces the Phase 32 invariant: a natural raid boss that is not tracked in ACTIVE should not
     * exist. Bound to ServerEntityEvents.ENTITY_LOAD, this is what actually removes bosses whose
     * despawn timer expired while their chunk was unloaded, bosses orphaned by an earlier session,
     * and anything the SERVER_STARTED sweep could not see because its chunk was not loaded yet.
     * Administrator-placed bosses are untagged as natural and are deliberately left alone.
     */
    public static void onNaturalBossLoaded(Entity entity, ServerLevel level) {
        if (spawningTrackedBoss) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!RaidBossEntityMarker.isNatural(pokemon) || !RaidBossEntityMarker.isRaidBoss(pokemon)) return;
        if (ACTIVE.containsKey(pokemon.getUUID())) return;
        if (pokemon.isBattling() || RaidLobbyManager.hasActiveLobby(pokemon)) return;

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            System.out.println("[CobbleRaids] Removed untracked natural raid boss " + pokemon.getUUID()
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
        if (ACTIVE.isEmpty()) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        // Null happens when a section merely stops being accessible without the entity being
        // removed at all; that is an unload, not a destruction.
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (ACTIVE.remove(pokemon.getUUID()) == null) return;

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            System.out.println("[CobbleRaids] Released raid slot for destroyed boss " + pokemon.getUUID()
                    + " (" + reason + ") in " + level.dimension().location() + ", active=" + ACTIVE.size());
        }
    }

    private static boolean hasNearbyPlayer(MinecraftServer server, PokemonEntity boss, double radius) {
        double radiusSqr = radius * radius;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == boss.level() && player.isAlive() && !player.isSpectator()
                    && player.distanceToSqr(boss) <= radiusSqr) return true;
        }
        return false;
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
    private static void purgeRemoved(MinecraftServer server) {
        ACTIVE.entrySet().removeIf(entry -> {
            PokemonEntity boss = resolveBoss(server, entry.getKey(), entry.getValue());
            return boss != null && boss.isRemoved();
        });
    }

    /**
     * A dimension-managing mod (e.g. Multiworld) can close a ServerLevel outright, not just unload
     * its chunks. When that happens any tracked boss there can never resolve again, and without this
     * it would otherwise sit in ACTIVE for its full despawn_seconds -- occupying a max_active_raids
     * slot for a raid nobody can ever reach. Server shutdown also fires this for every level, which
     * is harmless: onServerStopping already clears ACTIVE around the same time.
     */
    public static void onLevelUnloaded(MinecraftServer server, ServerLevel level) {
        ResourceLocation dimensionId = level.dimension().location();
        boolean removedAny = ACTIVE.entrySet().removeIf(entry -> entry.getValue().dimension().equals(dimensionId));
        if (removedAny && CobbleRaidsConfigManager.get().debugLogging()) {
            System.out.println("[CobbleRaids] Forgot natural raid boss(es) tracked in closed dimension " + dimensionId);
        }
    }

    /** Removes stale natural raid entities after a crash/restart. */
    public static void onServerStarted(MinecraftServer server) {
        ACTIVE.clear();
        NEXT_ALLOWED_TICK.clear();
        schedulerTick = 0L;
        int purged = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof PokemonEntity pokemon
                        && RaidBossEntityMarker.isNatural(pokemon)
                        && RaidBossEntityMarker.isRaidBoss(pokemon)) {
                    pokemon.discard();
                    purged++;
                }
            }
        }
        if (purged > 0) {
            System.out.println("[CobbleRaids] Removed " + purged
                    + " stale natural raid boss(es) from a prior server session.");
        }
    }

    /** Prevents persistent natural bosses from becoming orphaned on clean shutdown. */
    public static void onServerStopping(MinecraftServer server) {
        for (Map.Entry<UUID, ActiveSpawn> entry : List.copyOf(ACTIVE.entrySet())) {
            PokemonEntity boss = resolveBoss(server, entry.getKey(), entry.getValue());
            // Bosses in unloaded chunks are left to onNaturalBossLoaded on the next session.
            if (boss != null && !boss.isRemoved()) boss.discard();
        }
        ACTIVE.clear();
        NEXT_ALLOWED_TICK.clear();
        schedulerTick = 0L;
    }

    public static int activeCount(MinecraftServer server) {
        purgeRemoved(server);
        return ACTIVE.size();
    }

    /**
     * Drops a boss from tracking immediately. Admin despawns discard the entity directly rather
     * than going through maintainTrackedBosses, so without this the entry would otherwise sit in
     * ACTIVE for up to its full despawn_seconds: still counted against max_active_raids and
     * max_concurrent, and still blocking nearby spawns via min_distance_between_raids, since both
     * checks read ACTIVE regardless of whether the tracked boss still resolves. A no-op for a UUID
     * that was never natural (e.g. an admin-spawned boss), since Map.remove on a missing key is safe.
     */
    public static void forget(UUID bossId) {
        ACTIVE.remove(bossId);
    }

    /** Clears one definition's natural-spawn cooldown early. Returns false if it wasn't on cooldown. */
    public static boolean resetCooldown(ResourceLocation definitionId) {
        return NEXT_ALLOWED_TICK.remove(definitionId) != null;
    }

    /**
     * Definitions still on natural-spawn cooldown and the seconds left on each, soonest first.
     * Entries whose cooldown has already elapsed are skipped rather than reported as zero -- the map
     * keeps expired keys (it is bounded by the definition count), and they are not on cooldown.
     */
    public static List<Map.Entry<ResourceLocation, Long>> activeCooldowns() {
        List<Map.Entry<ResourceLocation, Long>> remaining = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Long> entry : NEXT_ALLOWED_TICK.entrySet()) {
            long ticksLeft = entry.getValue() - schedulerTick;
            if (ticksLeft > 0L) remaining.add(Map.entry(entry.getKey(), ticksLeft / 20L));
        }
        remaining.sort(Map.Entry.comparingByValue());
        return remaining;
    }
}
