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

    /** Operator view: the tuning numbers deliberately kept out of the player-facing command. */
    static int definition(CommandSourceStack source, String rawName) {
        RaidDefinition definition = resolve(source, rawName);
        if (definition == null) return 0;

        RaidDefinition.Spawn spawn = definition.spawn();
        source.sendSuccess(() -> CommandFormat.header(CommandFormat.shortId(definition.id()))
                .append(Component.literal("  " + definition.rarityTier().serializedName())
                        .withStyle(RaidTierPresentation.color(definition.rarityTier()))), false);

        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("combat", 10)
                + definition.baseHealth() + " hp · +"
                + Math.round(definition.scaling().healthPerExtraPlayer() * 100) + "% per extra player · "
                + definition.timeLimitSeconds() + "s limit · flee "
                + (definition.allowFlee() ? "on" : "off")), false);
        // Only shown when the definition actually pins something, so the 130 shipped definitions
        // read exactly as they did before traits existed.
        if (!definition.traits().isEmpty()) {
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("traits", 10)
                    + describeTraits(definition.traits())), false);
        }
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("recruit", 10)
                + definition.recruitment().durationSeconds() + "s · radius "
                + definition.recruitment().radius() + " · max "
                + definition.recruitment().maxPlayers() + " players"), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("spawn", 10)
                + (spawn.enabled() ? "enabled" : "DISABLED") + " · weight " + spawn.weight()
                + " · cooldown " + spawn.cooldownSeconds() + "s · max " + spawn.maxConcurrent()), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("timers", 10)
                + "despawn " + spawn.despawnSeconds() + "s unattended · lifetime "
                + spawn.maxLifetimeSeconds() + "s total"), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("where", 10) + habitats(definition.spawn())), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("when", 10) + times(definition.spawn())
                + " · " + dimensions(definition.spawn())), false);

        RaidDefinition.Rewards rewards = definition.rewards();
        for (RaidDefinition.RewardChoice choice : rewards.choices().values()) {
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("reward", 10)
                    + choice.id() + " · " + choice.items().size() + " guaranteed · "
                    + choice.chanceItems().size() + " chance"), false);
        }
        RaidDefinition.ContributionBonus bonus = rewards.contributionBonus();
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("bonus", 10)
                + (bonus.enabled() ? "enabled" : "disabled") + " · " + bonus.tiers().size()
                + " tiers · pool " + bonus.pool().size()), false);
        return 1;
    }

    static String describeTraits(com.cobbleraids.config.RaidBossTraits traits) {
        List<String> parts = new ArrayList<>();
        if (traits.nature() != null) parts.add(traits.nature());
        if (traits.ability() != null) parts.add(traits.ability());
        if (traits.gender() != null) parts.add(traits.gender());
        if (traits.form() != null) parts.add("form " + traits.form());
        if (traits.teraType() != null) parts.add("tera " + traits.teraType());
        if (traits.heldItem() != null) parts.add(CommandFormat.shortId(
                ResourceLocation.parse(traits.heldItem())));
        if (!traits.ivs().isEmpty()) parts.add("ivs " + traits.ivs());
        if (!traits.evs().isEmpty()) parts.add("evs " + traits.evs());
        return String.join(" · ", parts);
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
