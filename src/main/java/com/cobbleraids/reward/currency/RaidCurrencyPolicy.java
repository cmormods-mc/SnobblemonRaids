package com.cobbleraids.reward.currency;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.RaidRarityTier;
import java.math.BigInteger;

/**
 * Turns a rarity tier and a contribution share into an amount of currency.
 *
 * <p>Kept free of Minecraft types so the arithmetic can be tested without a server, the same way
 * ContributionMath is. This class owns the whole question of "how much"; a backend only knows how
 * to move an amount someone else decided on.
 *
 * <p>The numbers themselves are deliberately not chosen yet. Every tier defaults to zero, which
 * means no payout at all rather than a figure nobody picked -- pricing a raid against a server's
 * shops is a balance decision, and a plausible-looking default would quietly become the balance by
 * accident.
 */
public final class RaidCurrencyPolicy {

    /** Matches ContributionMath's tolerance, so a share that clears a threshold there clears it here. */
    private static final double EPSILON = 1.0e-9;

    private RaidCurrencyPolicy() {}

    /**
     * The payout for one claim, or {@link BigInteger#ZERO} when this claim earns nothing.
     *
     * <p>Scaling floors rather than rounds: a player with a 1% share of a 50-dollar raid gets
     * nothing rather than one dollar. Floor is the predictable direction -- it can never pay out
     * more than the tier's configured amount across a whole raid, whatever the shares are.
     */
    public static BigInteger payout(CobbleRaidsConfig.Currency config, RaidRarityTier tier, double contributionPercentage) {
        if (config == null || tier == null || !config.enabled() || config.isNoOp()) return BigInteger.ZERO;

        double share = Double.isNaN(contributionPercentage) ? 0.0 : Math.max(0.0, Math.min(100.0, contributionPercentage));
        if (share + EPSILON < config.minimumSharePercentage()) return BigInteger.ZERO;

        long base = config.amountFor(tier);
        if (base <= 0L) return BigInteger.ZERO;
        if (!config.scaleWithContribution()) return BigInteger.valueOf(base);

        long scaled = (long) Math.floor(base * (share / 100.0));
        return scaled <= 0L ? BigInteger.ZERO : BigInteger.valueOf(scaled);
    }
}
