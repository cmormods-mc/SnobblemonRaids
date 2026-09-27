package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.resources.ResourceLocation;

/**
 * One species a player has ever defeated in a raid, permanently. Unlike {@link BossSnapshot} this is
 * never spent: {@code level}/{@code shiny}/{@code ivPercent}/{@code evPercent} are pinned to the
 * player's first defeat of the species, and {@code timesDefeated} only ever grows.
 */
public record TrophyEntry(
        ResourceLocation species,
        int level,
        boolean shiny,
        int ivPercent,
        int evPercent,
        RaidRarityTier rarityTier,
        long firstDefeatedAtEpochMs,
        int timesDefeated
) {
}
