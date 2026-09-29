package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.resources.ResourceLocation;

/**
 * One species a player has ever defeated in a raid, permanently. Unlike {@link BossSnapshot} this is
 * never spent: {@code level}/{@code shiny}/{@code ivPercent}/{@code evPercent} are pinned to the
 * player's first defeat of the species, and {@code timesDefeated} only ever grows.
 *
 * <p>{@code firstCapturedAtEpochMs} is {@code 0} for a species never caught through the Raid Capture
 * Protocol -- a real epoch millisecond is never zero in practice, so this doubles as the "never
 * captured" flag without a separate boolean, the same convention {@code timesDefeated} already uses
 * implicitly (it is never written below 1). {@code bestStabilizationScorePercent} is the highest
 * stabilization quality (0..100) ever achieved capturing this species; it exists for a future
 * "Perfect Protocol" cosmetic, not read by anything yet. A defeat and a capture of the same species
 * pin their stats independently -- capturing does not touch the defeat fields above, and vice versa.
 *
 * <p>{@code firstRenownTitle} is the first renowned title ever beaten of this species, empty if none
 * -- pinned the same way {@code firstCapturedAtEpochMs} is, even if a later renowned repeat rolls a
 * different title. {@code timesRenownDefeated} counts every renowned defeat, pinned title or not.
 */
public record TrophyEntry(
        ResourceLocation species,
        int level,
        boolean shiny,
        int ivPercent,
        int evPercent,
        RaidRarityTier rarityTier,
        long firstDefeatedAtEpochMs,
        int timesDefeated,
        long firstCapturedAtEpochMs,
        int timesCaptured,
        int bestStabilizationScorePercent,
        int timesRenownDefeated,
        String firstRenownTitle
) {
    public TrophyEntry {
        firstRenownTitle = firstRenownTitle == null ? "" : firstRenownTitle;
    }

    /** True once this species has been caught at least once through the Raid Capture Protocol. */
    public boolean everCaptured() {
        return firstCapturedAtEpochMs > 0;
    }

    /** True once this species has been defeated renowned at least once. */
    public boolean everRenowned() {
        return !firstRenownTitle.isEmpty();
    }
}
