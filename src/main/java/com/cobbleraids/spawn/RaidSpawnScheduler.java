package com.cobbleraids.spawn;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
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
    private record ActiveSpawn(
            ResourceLocation definitionId,
            ResourceLocation dimension,
            BlockPos position,
            long spawnedAtTick,
            long lastNearbyPlayerTick,
            int despawnSeconds
    ) {
        ActiveSpawn withLastNearbyPlayerTick(long tick) {
            return new ActiveSpawn(definitionId, dimension, position, spawnedAtTick, tick, despawnSeconds);
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
        if (activeInDimension(dimensionId) >= config.maxActiveRaidsPerDimension()) return;

        Optional<BlockPos> position = RaidSpawnPositionFinder.findLand(level, player, config);
        if (position.isEmpty()) return;
        BlockPos pos = position.get();
        if (isTooCloseToAnotherRaid(level, pos, config.minDistanceBetweenRaids())) return;

        Holder<Biome> biomeHolder = level.getBiome(pos);
        ResourceLocation biomeId = biomeHolder.unwrapKey().map(key -> key.location()).orElse(null);
        RaidSpawnContext context = new RaidSpawnContext(dimensionId, biomeId, biomeHolder, level.getDayTime());

        List<RaidDefinition> eligible = RaidDefinitionRegistry.all().stream()
                .filter(context::matches)
                .filter(RaidSpawnScheduler::offCooldown)
                .filter(RaidSpawnScheduler::belowDefinitionCap)
                .toList();
        if (eligible.isEmpty()) return;

        RaidDefinition selected = RaidTierSelector.select(
                eligible,
                RaidDefinition::rarityTier,
                definition -> definition.spawn().weight(),
                config.tierWeights(),
                ThreadLocalRandom.current()
        );
        if (selected != null) spawnTracked(level, pos, biomeId, dimensionId, selected);
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
                schedulerTick,
                selected.spawn().despawnSeconds()
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
                .append(Component.literal(tier.displayName() + " ").withStyle(tierColor(tier)))
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

    private static ChatFormatting tierColor(RaidRarityTier tier) {
        return switch (tier) {
            case STARTER -> ChatFormatting.GREEN;
            case POWERHOUSE -> ChatFormatting.AQUA;
            case LEGENDARY -> ChatFormatting.GOLD;
            case MYTHICAL -> ChatFormatting.LIGHT_PURPLE;
        };
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
        Map<RaidRarityTier, Double> odds = RaidTierSelector.normalizedPercentages(counts, config.tierWeights());

        source.sendSuccess(() -> Component.literal("CobbleRaids wild spawn director")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(" Location: " + (biomeId == null ? "unknown" : biomeId)
                + " | " + dimensionId + " | "
                + RaidDefinition.SpawnTime.current(level.getDayTime()).name().toLowerCase(Locale.ROOT)), false);
        source.sendSuccess(() -> Component.literal(" Active: " + ACTIVE.size() + "/" + config.maxActiveRaids()
                + " globally, " + activeInDimension(dimensionId) + "/"
                + config.maxActiveRaidsPerDimension() + " in this dimension"), false);

        for (RaidRarityTier tier : RaidRarityTier.values()) {
            List<String> names = eligible.stream()
                    .filter(definition -> definition.rarityTier() == tier)
                    .map(definition -> definition.species().getPath())
                    .toList();
            source.sendSuccess(() -> Component.literal(String.format(
                            Locale.ROOT,
                            " %s: %.2f%% | %d eligible | %s",
                            tier.displayName(), odds.getOrDefault(tier, 0.0), names.size(), namesSummary(names)))
                    .withStyle(tierColor(tier)), false);
        }

        int blocked = environmental.size() - eligible.size();
        source.sendSuccess(() -> Component.literal(" Eligible now: " + eligible.size() + "/"
                + environmental.size() + " environmental matches"
                + (blocked == 0 ? "" : " (" + blocked + " on cooldown/at cap)")
                + (config.enabled() ? "" : " | NATURAL SPAWNING DISABLED")), false);
        return eligible.size();
    }

    private static String namesSummary(List<String> names) {
        if (names.isEmpty()) return "none";
        int shown = Math.min(12, names.size());
        String result = String.join(", ", names.subList(0, shown));
        return shown == names.size() ? result : result + " +" + (names.size() - shown) + " more";
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
                        + definition.rarityTier().displayName() + " " + definition.species().getPath()
                        + " at " + format(boss.position())
                        + ". Random chance and the prior cooldown were bypassed; natural tracking is active.")
                        .withStyle(ChatFormatting.GREEN), true);
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

        List<RaidDefinition> matches = RaidDefinitionRegistry.all().stream()
                .filter(definition -> definition.species().getNamespace().equals("cobblemon"))
                .filter(definition -> definition.species().getPath().equalsIgnoreCase(pokemonName))
                .toList();
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(matches.isEmpty()
                    ? "No raid definition uses species '" + pokemonName + "'."
                    : "Multiple raid definitions use species '" + pokemonName + "'."));
            return null;
        }
        return matches.getFirst();
    }

    private static String format(Vec3 position) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", position.x, position.y, position.z);
    }

    private static boolean offCooldown(RaidDefinition definition) {
        return schedulerTick >= NEXT_ALLOWED_TICK.getOrDefault(definition.id(), 0L);
    }

    private static boolean belowDefinitionCap(RaidDefinition definition) {
        long active = ACTIVE.values().stream()
                .filter(spawn -> spawn.definitionId().equals(definition.id()))
                .count();
        return active < definition.spawn().maxConcurrent();
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
        Iterator<Map.Entry<UUID, ActiveSpawn>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ActiveSpawn> entry = iterator.next();
            ActiveSpawn active = entry.getValue();
            PokemonEntity boss = resolveBoss(server, entry.getKey(), active);

            // A boss that resolves and still reports removed is genuinely gone: killed, discarded
            // or captured. An unloaded boss does not resolve at all and is handled below.
            if (boss != null && boss.isRemoved()) {
                iterator.remove();
                continue;
            }

            if (boss != null) {
                // Recruitment and combat own the boss lifecycle while either is active.
                if (boss.isBattling() || RaidLobbyManager.hasActiveLobby(boss)) {
                    entry.setValue(active.withLastNearbyPlayerTick(schedulerTick));
                    continue;
                }
                if (hasNearbyPlayer(server, boss, config.despawnPlayerRadius())) {
                    entry.setValue(active.withLastNearbyPlayerTick(schedulerTick));
                    continue;
                }
            }
            // boss == null means the chunk holding it is not loaded, which is itself proof that no
            // player is near it. Idle time keeps accruing rather than the entry being dropped.

            long idleTicks = schedulerTick - active.lastNearbyPlayerTick();
            if (idleTicks < active.despawnSeconds() * 20L) continue;

            if (CobbleRaidsConfigManager.get().debugLogging()) {
                System.out.println("[CobbleRaids] Despawning unattended wild raid " + active.definitionId()
                        + (boss == null ? " (deferred: chunk not loaded)" : ""));
            }
            if (boss != null) boss.discard();
            // An unloaded boss cannot be discarded from here. Dropping it from ACTIVE hands it to
            // onNaturalBossLoaded, which removes any untracked natural boss the moment it loads.
            iterator.remove();
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
     */
    private static void purgeRemoved(MinecraftServer server) {
        ACTIVE.entrySet().removeIf(entry -> {
            PokemonEntity boss = resolveBoss(server, entry.getKey(), entry.getValue());
            return boss != null && boss.isRemoved();
        });
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
}
