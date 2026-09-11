package com.cobbleraids.spawn;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The operator-facing half of wild spawning: /cobbleraids spawninfo and /cobbleraids testwild.
 *
 * <p>Kept apart from RaidSpawnScheduler because neither runs on the tick path -- both are answers to
 * a human typing a command, and both are mostly presentation. Leaving them in the scheduler made a
 * class that is supposed to say when to attempt a spawn into the largest formatter in the mod.
 */
public final class RaidSpawnCommands {

    private RaidSpawnCommands() {}

    public static int sendSpawnInfo(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("/cobbleraids spawninfo must be run by a player."));
            return 0;
        }

        RaidSpawnScheduler.purgeRemoved(source.getServer());
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
        source.sendSuccess(() -> CommandFormat.row("active " + RaidSpawnScheduler.trackedCount() + "/" + config.maxActiveRaids()
                + " global · " + RaidSpawnScheduler.trackedInDimension(dimensionId) + "/"
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

        RaidSpawnScheduler.purgeRemoved(source.getServer());
        ServerLevel level = source.getLevel();
        ResourceLocation dimensionId = level.dimension().location();
        if (RaidSpawnScheduler.trackedCount() >= config.maxActiveRaids()
                || RaidSpawnScheduler.trackedInDimension(dimensionId) >= config.maxActiveRaidsPerDimension()
                || !RaidSpawnScheduler.belowDefinitionCap(definition)) {
            source.sendFailure(Component.literal("A natural raid cap is full. Despawn an active boss and retry."));
            return 0;
        }

        for (int search = 0; search < 16; search++) {
            Optional<BlockPos> candidate = RaidSpawnPositionFinder.findLand(level, player, config);
            if (candidate.isEmpty()) continue;
            BlockPos pos = candidate.get();
            if (RaidSpawnScheduler.tooCloseToAnotherRaid(level, pos, config.minDistanceBetweenRaids())) continue;

            Holder<Biome> biomeHolder = level.getBiome(pos);
            ResourceLocation biomeId = biomeHolder.unwrapKey().map(key -> key.location()).orElse(null);
            RaidSpawnContext context = new RaidSpawnContext(
                    dimensionId, biomeId, biomeHolder, level.getDayTime());
            if (!context.matches(definition)) continue;

            try {
                PokemonEntity boss = RaidSpawnScheduler.spawnTracked(level, pos, biomeId, dimensionId, definition);
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
}
