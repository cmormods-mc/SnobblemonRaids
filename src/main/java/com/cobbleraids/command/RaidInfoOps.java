package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Two views of one raid definition.
 *
 * {@link #info} is the only unrestricted CobbleRaids command besides claiming a reward, so it is
 * written for a player who has never read the datapack: friendly names, no ids, no tuning numbers,
 * and it answers the question the rest of the tree could not -- "where and when does this appear?".
 * {@link #definition} is the operator counterpart carrying the tuning values.
 */
final class RaidInfoOps {
    private RaidInfoOps() {}

    static int info(CommandSourceStack source, String rawName) {
        RaidDefinition definition = resolve(source, rawName);
        if (definition == null) return 0;

        String species = capitalize(definition.species().getPath());
        source.sendSuccess(() -> CommandFormat.header(species + " raid")
                .append(Component.literal("  " + definition.rarityTier().displayName())
                        .withStyle(RaidTierPresentation.color(definition.rarityTier()))), false);

        // "75+" rather than "75" when dynamic levels are on: the definition's level is a floor
        // that a strong party raises, so stating it bare would be wrong for half the raids run.
        String level = CobbleRaidsConfigManager.get().dynamicLevel().enabled()
                ? definition.level() + "+"
                : String.valueOf(definition.level());
        source.sendSuccess(() -> CommandFormat.row("Level " + level
                + " · up to " + definition.recruitment().maxPlayers() + " players"
                + " · " + definition.recruitment().durationSeconds() + "s to join"), false);

        if (!definition.spawn().enabled()) {
            source.sendSuccess(() -> CommandFormat.row("Does not appear in the wild.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        source.sendSuccess(() -> CommandFormat.row("Found in: " + habitats(definition.spawn())), false);
        source.sendSuccess(() -> CommandFormat.row("Time: " + times(definition.spawn())
                + " · " + dimensions(definition.spawn())), false);
        source.sendSuccess(() -> CommandFormat.hint(" Right-click one to join; the raid starts when the timer ends."), false);
        return 1;
    }

    private static RaidDefinition resolve(CommandSourceStack source, String rawName) {
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        RaidDefinition definition = RaidDefinitionRegistry.findSpecies(name);
        if (definition == null) {
            source.sendFailure(Component.literal("No raid uses the species '" + rawName + "'."));
            return null;
        }
        return definition;
    }

    /**
     * Biome tags in this datapack are shaped cobbleraids:raid_types/fire, so the last path segment is
     * the part a player recognises. Falls back to plain biome ids, then to "anywhere" -- an empty
     * biome list genuinely means any biome in the allowed dimensions.
     *
     * <p>Takes the {@code Spawn} sub-record rather than the whole {@link RaidDefinition}: it is all
     * this reads, and it is what {@code RaidInfoOpsTest} builds directly instead of a full
     * definition's unrelated fields.
     */
    static String habitats(RaidDefinition.Spawn spawn) {
        List<String> names = spawn.biomeTags().stream().map(RaidInfoOps::lastSegment).toList();
        if (names.isEmpty()) names = spawn.biomes().stream().map(RaidInfoOps::lastSegment).toList();
        return names.isEmpty() ? "any biome" : CommandFormat.names(names, 6);
    }

    static String times(RaidDefinition.Spawn spawn) {
        List<RaidDefinition.SpawnTime> times = spawn.times();
        if (times.contains(RaidDefinition.SpawnTime.ALL_DAY)) return "any time";
        return CommandFormat.names(times.stream()
                .map(time -> time.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .toList(), 8);
    }

    static String dimensions(RaidDefinition.Spawn spawn) {
        List<String> dims = spawn.dimensions().stream()
                .map(CommandFormat::shortId).toList();
        return dims.isEmpty() ? "any dimension" : CommandFormat.names(dims, 4);
    }

    private static String lastSegment(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return (slash < 0 ? path : path.substring(slash + 1)).replace('_', ' ');
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
