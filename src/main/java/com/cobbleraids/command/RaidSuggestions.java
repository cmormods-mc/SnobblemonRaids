package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Tab completion for the operator command tree. Every provider reads the registry at completion
 * time rather than caching: raid definitions are datapack-driven and swap wholesale on
 * /reload, so a cached list would start suggesting names that no longer resolve.
 *
 * Sorted alphabetically because the boss pool is 130 entries -- long enough that scanning an
 * arbitrary order is worse than no list at all.
 */
final class RaidSuggestions {
    private RaidSuggestions() {}

    /** Species names, for the commands that take a bare Cobblemon species (spawn, testwild). */
    static final SuggestionProvider<CommandSourceStack> SPECIES = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    RaidDefinitionRegistry.all().stream()
                            .map(definition -> definition.species().getPath())
                            .distinct()
                            .sorted(),
                    builder);

    /**
     * Full definition ids, for the commands keyed by definition rather than species.
     *
     * Deliberately not SharedSuggestionProvider.suggestResource: with no colon typed, that matches
     * the input against the *namespace*, and falls back to the path only for minecraft: ids. Every
     * definition here is cobbleraids:, so typing "mew" offered nothing at all -- you had to know to
     * type the namespace first. Matching the path as well is the whole point of the suggestion.
     */
    static final SuggestionProvider<CommandSourceStack> DEFINITIONS = (context, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        RaidDefinitionRegistry.all().stream()
                .map(RaidDefinition::id)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .filter(id -> id.toString().startsWith(typed) || id.getPath().startsWith(typed))
                .forEach(id -> builder.suggest(id.toString()));
        return builder.buildFuture();
    };

    static final SuggestionProvider<CommandSourceStack> TIERS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    Arrays.stream(RaidRarityTier.values()).map(RaidRarityTier::serializedName), builder);
}
