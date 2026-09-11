package com.cobbleraids.reward.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.RaidRarityTier;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout arithmetic, which is the whole of "how much" -- a backend only moves an amount decided
 * here. Every tier ships at zero on purpose, so the first tests are really asserting that an
 * unconfigured server pays nothing at all rather than something nobody chose.
 */
class RaidCurrencyPolicyTest {

    private static CobbleRaidsConfig.Currency flat(long starter, long powerhouse, long legendary, long mythical) {
        return new CobbleRaidsConfig.Currency(true, starter, powerhouse, legendary, mythical, false, 0.0);
    }

    private static CobbleRaidsConfig.Currency scaled(long amount, double minimumShare) {
        return new CobbleRaidsConfig.Currency(true, amount, amount, amount, amount, true, minimumShare);
    }

    @Test
    @DisplayName("ships inert: defaults pay nothing to anyone")
    void defaultsPayNothing() {
        CobbleRaidsConfig.Currency defaults = CobbleRaidsConfig.Currency.defaults();

        assertFalse(defaults.enabled());
        assertTrue(defaults.isNoOp());
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(defaults, tier, 100.0));
        }
    }

    @Test
    @DisplayName("amounts configured but the feature off still pays nothing")
    void disabledOverridesAmounts() {
        CobbleRaidsConfig.Currency off = new CobbleRaidsConfig.Currency(false, 500L, 500L, 500L, 500L, false, 0.0);

        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(off, RaidRarityTier.LEGENDARY, 100.0));
    }

    @Test
    @DisplayName("a null config pays nothing instead of throwing inside a claim")
    void nullConfigIsSafe() {
        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(null, RaidRarityTier.STARTER, 50.0));
        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(flat(10L, 10L, 10L, 10L), null, 50.0));
    }

    @Test
    @DisplayName("each tier is paid its own amount")
    void tiersAreIndependent() {
        CobbleRaidsConfig.Currency config = flat(10L, 20L, 30L, 40L);

        assertEquals(BigInteger.valueOf(10L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 100.0));
        assertEquals(BigInteger.valueOf(20L), RaidCurrencyPolicy.payout(config, RaidRarityTier.POWERHOUSE, 100.0));
        assertEquals(BigInteger.valueOf(30L), RaidCurrencyPolicy.payout(config, RaidRarityTier.LEGENDARY, 100.0));
        assertEquals(BigInteger.valueOf(40L), RaidCurrencyPolicy.payout(config, RaidRarityTier.MYTHICAL, 100.0));
    }

    @Test
    @DisplayName("a tier left at zero pays nothing while its neighbours pay")
    void zeroTierIsSkipped() {
        CobbleRaidsConfig.Currency config = flat(0L, 20L, 0L, 40L);

        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 100.0));
        assertEquals(BigInteger.valueOf(20L), RaidCurrencyPolicy.payout(config, RaidRarityTier.POWERHOUSE, 100.0));
        assertFalse(config.isNoOp());
    }

    @Test
    @DisplayName("flat mode pays every eligible victor the same, whatever their share")
    void flatModeIgnoresShare() {
        CobbleRaidsConfig.Currency config = flat(100L, 100L, 100L, 100L);

        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 5.0));
        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 100.0));
    }

    @Test
    @DisplayName("scaled mode splits the tier amount by damage share")
    void scaledModeSplitsByShare() {
        CobbleRaidsConfig.Currency config = scaled(1_000L, 0.0);

        assertEquals(BigInteger.valueOf(1_000L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 100.0));
        assertEquals(BigInteger.valueOf(500L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 50.0));
        assertEquals(BigInteger.valueOf(250L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 25.0));
    }

    @Test
    @DisplayName("scaled payouts across a whole raid never exceed one tier amount")
    void scaledGroupNeverOverpays() {
        // Four players splitting a raid 40/35/15/10 must not cost more than a solo win would.
        CobbleRaidsConfig.Currency config = scaled(1_000L, 0.0);
        double[] shares = {40.0, 35.0, 15.0, 10.0};

        BigInteger total = BigInteger.ZERO;
        for (double share : shares) {
            total = total.add(RaidCurrencyPolicy.payout(config, RaidRarityTier.LEGENDARY, share));
        }

        assertTrue(total.compareTo(BigInteger.valueOf(1_000L)) <= 0,
                "four scaled payouts totalled " + total + ", above the tier amount of 1000");
    }

    @Test
    @DisplayName("scaling floors, so a token contribution earns nothing")
    void scalingFloorsToZero() {
        CobbleRaidsConfig.Currency config = scaled(50L, 0.0);

        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 1.0));
        assertEquals(BigInteger.valueOf(1L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 2.0));
    }

    @Test
    @DisplayName("the minimum share withholds below it and pays exactly on it")
    void minimumShareIsInclusive() {
        CobbleRaidsConfig.Currency config = new CobbleRaidsConfig.Currency(true, 100L, 100L, 100L, 100L, false, 20.0);

        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 19.99));
        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 20.0));
        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 20.01));
    }

    @Test
    @DisplayName("threshold tolerance matches ContributionMath, so both fire on the same share")
    void thresholdToleranceMatchesContributionMath() {
        // A third of a raid is not exactly 33.333..., and ContributionMath already allows 1e-9 for
        // that. The two must agree, or a player can clear the bonus-roll threshold and miss the
        // currency threshold in the same claim.
        CobbleRaidsConfig.Currency config = new CobbleRaidsConfig.Currency(true, 100L, 100L, 100L, 100L, false, 33.3);
        double share = 100.0 / 3.0 - 0.0333333333;

        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, share));
    }

    @Test
    @DisplayName("a nonsensical share is clamped rather than paying out nonsense")
    void oddSharesAreClamped() {
        CobbleRaidsConfig.Currency config = scaled(100L, 0.0);

        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, -25.0));
        assertEquals(BigInteger.ZERO, RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, Double.NaN));
        assertEquals(BigInteger.valueOf(100L), RaidCurrencyPolicy.payout(config, RaidRarityTier.STARTER, 250.0));
    }

    @Test
    @DisplayName("the config rejects amounts that cannot be a sane payout")
    void configRejectsBadAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Currency(true, -1L, 0L, 0L, 0L, false, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Currency(true, 1_000_000_001L, 0L, 0L, 0L, false, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Currency(true, 0L, 0L, 0L, 0L, false, 100.5));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Currency(true, 0L, 0L, 0L, 0L, false, -1.0));
    }
}
