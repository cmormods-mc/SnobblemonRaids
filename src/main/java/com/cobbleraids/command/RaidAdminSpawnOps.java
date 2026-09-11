package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class RaidAdminSpawnOps {
    private RaidAdminSpawnOps() {}

    /** Per-tier counts. 130 individual rows scroll a chat window straight off the screen. */
    static int list(CommandSourceStack source) {
        List<RaidDefinition> definitions = sorted();
        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No raid definitions are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        source.sendSuccess(() -> CommandFormat.header("Raid definitions (" + definitions.size() + ")"), false);
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            List<String> names = definitions.stream()
                    .filter(definition -> definition.rarityTier() == tier)
                    .map(definition -> definition.species().getPath())
                    .toList();
            if (names.isEmpty()) continue;
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(tier.serializedName(), 11)
                            + CommandFormat.pad(Integer.toString(names.size()), 4)
                            + CommandFormat.names(names, 3))
                    .withStyle(RaidTierPresentation.color(tier)), false);
        }
        source.sendSuccess(() -> CommandFormat.hint(" /cobbleraids list <tier> for full detail"), false);
        return definitions.size();
    }

    /** Full rows for one tier only, which is a length a chat window can actually show. */
    static int listTier(CommandSourceStack source, String rawTier) {
        RaidRarityTier tier;
        try {
            tier = RaidRarityTier.parse(rawTier);
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.literal("Unknown tier '" + rawTier + "'. Use one of: "
                    + Arrays.stream(RaidRarityTier.values())
                            .map(RaidRarityTier::serializedName).collect(Collectors.joining(", "))));
            return 0;
        }

        List<RaidDefinition> definitions = sorted().stream()
                .filter(definition -> definition.rarityTier() == tier).toList();
        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No " + tier.serializedName() + " definitions are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        // A tier usually shares one level and one spawn mode across every entry, and repeating
        // "Lv.75  wild" down 27 identical rows is the noise this listing is meant to avoid. Any
        // property that is the same for all of them is stated once in the header instead, leaving
        // the rows carrying only what actually differs.
        boolean uniformLevel = definitions.stream().mapToInt(RaidDefinition::level).distinct().count() == 1;
        boolean uniformSpawn = definitions.stream().map(d -> d.spawn().enabled()).distinct().count() == 1;
        String all = definitions.size() > 1 ? "all " : "";
        String headline = tier.displayName() + " definitions (" + definitions.size() + ")"
                + (uniformLevel ? " · " + all + "Lv." + definitions.getFirst().level() : "")
                + (uniformSpawn ? " · " + all + (definitions.getFirst().spawn().enabled() ? "wild" : "manual") : "");

        source.sendSuccess(() -> CommandFormat.header(headline)
                .withStyle(RaidTierPresentation.color(tier)), false);
        for (RaidDefinition definition : definitions) {
            // The id is only worth a column when it is not simply cobbleraids:<species>, which is
            // what every shipped definition uses; showing it always just repeated the name.
            String expectedId = "cobbleraids:" + definition.species().getPath();
            String row = uniformLevel && uniformSpawn
                    ? definition.species().getPath()
                    : CommandFormat.pad(definition.species().getPath(), 14)
                            + (uniformLevel ? "" : CommandFormat.pad("Lv." + definition.level(), 7))
                            + (uniformSpawn ? "" : definition.spawn().enabled() ? "wild" : "manual");
            String suffix = definition.id().toString().equals(expectedId)
                    ? "" : "  " + CommandFormat.shortId(definition.id());
            source.sendSuccess(() -> CommandFormat.row(row + suffix), false);
        }
        return definitions.size();
    }

    private static List<RaidDefinition> sorted() {
        return RaidDefinitionRegistry.all().stream()
                .sorted(Comparator.comparing(definition -> definition.species().getPath())).toList();
    }

    static int spawnNearPlayer(CommandSourceStack source, String rawId) {
        ServerPlayer player;
        try { player = source.getPlayerOrException(); }
        catch (Exception ex) {
            source.sendFailure(Component.literal("This form requires a player. From console, provide explicit coordinates."));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        return spawnAt(source, rawId, player.position().add(look.x * 3.0, 0.0, look.z * 3.0));
    }

    static int spawnAt(CommandSourceStack source, String rawPokemonName, Vec3 position) {
        String pokemonName = rawPokemonName.trim().toLowerCase(Locale.ROOT);
        if (pokemonName.isEmpty() || pokemonName.contains(":")) {
            source.sendFailure(Component.literal("Use the Cobblemon species name only, for example: /cobbleraids spawn garchomp"));
            return 0;
        }

        List<RaidDefinition> matches = RaidDefinitionRegistry.findBySpeciesName(pokemonName);
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No loaded CobbleRaids definition uses Cobblemon species '"
                    + pokemonName + "'. Use /cobbleraids list."));
            return 0;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(definition -> definition.id().toString())
                    .collect(Collectors.joining(", "));
            source.sendFailure(Component.literal("Multiple raid definitions use '" + pokemonName
                    + "': " + ids + ". Keep one definition per species for the simple spawn command."));
            return 0;
        }

        RaidDefinition definition = matches.getFirst();
        try {
            PokemonEntity boss = RaidBossSpawner.spawnAt(source.getLevel(), position, definition);
            source.sendSuccess(() -> Component.literal("Spawned " + pokemonName + " raid at "
                    + CommandFormat.coords(boss.getX(), boss.getY(), boss.getZ()) + " in "
                    + CommandFormat.shortId(source.getLevel().dimension().location()))
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to spawn " + pokemonName + ": " + ex.getMessage()));
            return 0;
        }
    }


    static int listCooldowns(CommandSourceStack source) {
        List<Map.Entry<ResourceLocation, Long>> cooldowns = RaidSpawnScheduler.activeCooldowns();
        if (cooldowns.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No definitions are on natural-spawn cooldown.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header("On spawn cooldown (" + cooldowns.size() + ")"), false);
        for (Map.Entry<ResourceLocation, Long> entry : cooldowns) {
            source.sendSuccess(() -> CommandFormat.row(
                    CommandFormat.pad(CommandFormat.shortId(entry.getKey()), 16)
                            + CommandFormat.duration(entry.getValue()) + " left"), false);
        }
        return cooldowns.size();
    }

    static int resetCooldown(CommandSourceStack source, ResourceLocation definitionId) {
        if (RaidDefinitionRegistry.get(definitionId) == null) {
            source.sendFailure(Component.literal("No loaded raid definition '" + definitionId + "'. Use /cobbleraids list."));
            return 0;
        }
        boolean wasOnCooldown = RaidSpawnScheduler.resetCooldown(definitionId);
        source.sendSuccess(() -> Component.literal(wasOnCooldown
                ? "Cleared natural-spawn cooldown for " + definitionId + "."
                : definitionId + " was not on cooldown.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
