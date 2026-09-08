package com.cobbleraids.catching;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;

/**
 * Decides a player's chance to keep the boss they just beat. This is the seam every catch mechanic
 * plugs into, and the only place a mechanic has to live.
 *
 * <p>Everything else in this package is mechanic-agnostic on purpose: {@link RaidPlayerRecords}
 * remembers what a player has done, {@link RaidCatchService} turns a chance into a Pokemon in their
 * party. Changing the rule means writing one implementation of this and nothing else. A points
 * economy would read a balance it maintains elsewhere; a reputation system reads
 * {@code record.raidsWon()}; a per-species progression reads {@code record.defeatsOf(id)}.
 *
 * <p>The shipped implementation is a flat per-tier chance that defaults to zero, so the
 * infrastructure is inert until a mechanic is chosen. It is not a proposal -- it is the smallest
 * thing that proves the wiring works end to end.
 */
@FunctionalInterface
public interface RaidCatchPolicy {

    /**
     * @param record  everything the player has done in raids before this one
     * @param context the raid just won
     * @return 0..1. Anything at or below zero means no attempt is offered at all, which is how a
     *         mechanic declines a tier rather than rolling and almost always failing.
     */
    double chanceFor(RaidPlayerRecord record, Context context);

    /** What a mechanic gets to reason about, kept to what a raid actually knows at victory. */
    record Context(RaidDefinition definition, double contributionPercentage, int participantCount) {}

    /**
     * Config-driven flat chance per rarity tier. Zero everywhere by default.
     *
     * <p>Deliberately ignores the player record. A mechanic that used history would be making the
     * design decision this class exists to defer.
     */
    RaidCatchPolicy TIER_CHANCE = (record, context) ->
            CobbleRaidsConfigManager.get().catching().chanceFor(context.definition().rarityTier());

    /** The rule in force. Swapped by whichever mechanic is chosen; one line, one place. */
    static RaidCatchPolicy active() {
        return TIER_CHANCE;
    }

    /** True when catching is switched on at all, so the victory path can skip the whole pass. */
    static boolean enabled() {
        CobbleRaidsConfig.Catching config = CobbleRaidsConfigManager.get().catching();
        return config.enabled() && !config.isNoOp();
    }
}
