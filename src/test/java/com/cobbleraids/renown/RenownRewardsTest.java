package com.cobbleraids.renown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What renown adds: the stat focus clamp, the payout multipliers, and the spawn roll. */
class RenownRewardsTest {

    @Test
    @DisplayName("a stat focus adds its EVs, but never past 252 in the stat or 510 in total")
    void focusRespectsBothLimits() {
        assertEquals(128, RenownRewards.focusEvs(0, 0, 128));
        assertEquals(252, RenownRewards.focusEvs(200, 200, 128), "per-stat cap");
        assertEquals(10, RenownRewards.focusEvs(0, 500, 128), "only the total's headroom is left");
        assertEquals(0, RenownRewards.focusEvs(0, 510, 128), "a fully invested boss gains nothing");
        assertEquals(252, RenownRewards.focusEvs(252, 510, 128), "never lowers what the definition set");
        assertEquals(0, RenownRewards.focusEvs(0, 0, 0));
    }

    @Test
    @DisplayName("points scale and floor, and a multiplier below one cannot shrink a payout")
    void pointsScale() {
        assertEquals(31, RenownRewards.points(25, 1.25));
        assertEquals(62, RenownRewards.points(50, 1.25));
        assertEquals(93, RenownRewards.points(75, 1.25));
        assertEquals(125, RenownRewards.points(100, 1.25));
        assertEquals(75, RenownRewards.points(75, 1.0));
        assertEquals(75, RenownRewards.points(75, 0.5));
        assertEquals(0, RenownRewards.points(0, 2.0));
        assertEquals(Integer.MAX_VALUE, RenownRewards.points(Integer.MAX_VALUE, 3.0));
    }

    @Test
    @DisplayName("currency scales and floors the same way")
    void currencyScales() {
        assertEquals(BigInteger.valueOf(15_000), RenownRewards.currency(BigInteger.valueOf(12_000), 1.25));
        assertEquals(BigInteger.valueOf(1_251), RenownRewards.currency(BigInteger.valueOf(1_001), 1.25));
        assertEquals(BigInteger.ZERO, RenownRewards.currency(BigInteger.ZERO, 1.25));
        assertEquals(BigInteger.ZERO, RenownRewards.currency(null, 1.25));
    }

    @Test
    @DisplayName("the renown roll honours its chance")
    void rollHonoursChance() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 1_000; i++) {
            assertFalse(RenownRewards.rolls(0.0, random));
            assertTrue(RenownRewards.rolls(1.0, random));
        }
        int hits = 0, rolls = 50_000;
        for (int i = 0; i < rolls; i++) if (RenownRewards.rolls(0.10, random)) hits++;
        assertEquals(0.10, hits / (double) rolls, 0.01);
    }
}
