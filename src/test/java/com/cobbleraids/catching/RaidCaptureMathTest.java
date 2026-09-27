package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RaidCaptureMathTest {

    @Test
    @DisplayName("dead center of the perfect zone scores 1.0")
    void perfectCenterScoresFull() {
        assertEquals(1.0, RaidCaptureMath.judge(0, 300, 120), 0.0);
        assertEquals(1.0, RaidCaptureMath.judge(-60, 300, 120), 0.0);
        assertEquals(1.0, RaidCaptureMath.judge(60, 300, 120), 0.0);
    }

    @Test
    @DisplayName("inside the good zone but outside the perfect zone scores 0.5")
    void goodZoneScoresHalf() {
        assertEquals(0.5, RaidCaptureMath.judge(61, 300, 120), 0.0);
        assertEquals(0.5, RaidCaptureMath.judge(150, 300, 120), 0.0);
        assertEquals(0.5, RaidCaptureMath.judge(-150, 300, 120), 0.0);
    }

    @Test
    @DisplayName("outside both zones scores 0.0, in either direction")
    void outsideZonesScoresZero() {
        assertEquals(0.0, RaidCaptureMath.judge(151, 300, 120), 0.0);
        assertEquals(0.0, RaidCaptureMath.judge(-1000, 300, 120), 0.0);
    }

    @Test
    @DisplayName("stabilization quality is the plain mean of the three pulse scores")
    void stabilizationQualityIsTheMean() {
        assertEquals(1.0, RaidCaptureMath.stabilizationQuality(1.0, 1.0, 1.0), 1.0e-9);
        assertEquals(0.5, RaidCaptureMath.stabilizationQuality(0.5, 0.5, 0.5), 1.0e-9);
        assertEquals(0.0, RaidCaptureMath.stabilizationQuality(0.0, 0.0, 0.0), 1.0e-9);
        assertEquals(1.0 / 3.0, RaidCaptureMath.stabilizationQuality(1.0, 0.0, 0.0), 1.0e-9);
    }

    @Test
    @DisplayName("a missed sequence never drops below the tier's floor")
    void zeroQualityFloorsAtBase() {
        assertEquals(0.04, RaidCaptureMath.finalChance(0.04, 0.015, 0.015, 0.0, 0.0), 1.0e-9);
    }

    @Test
    @DisplayName("a flawless sequence reaches exactly the tier's ceiling")
    void perfectQualityReachesCeiling() {
        assertEquals(0.07, RaidCaptureMath.finalChance(0.04, 0.015, 0.015, 1.0, 1.0), 1.0e-9);
    }

    @Test
    @DisplayName("bonuses are additive-only: partial quality lands strictly between floor and ceiling")
    void partialQualityIsBetweenFloorAndCeiling() {
        double chance = RaidCaptureMath.finalChance(0.08, 0.02, 0.02, 2.0 / 3.0, 0.5);
        assertEquals(0.08 + (2.0 / 3.0) * 0.02 + 0.5 * 0.02, chance, 1.0e-9);
    }

    @Test
    @DisplayName("an out-of-range quality is clamped rather than pushing the chance past the ceiling")
    void outOfRangeQualityIsClamped() {
        assertEquals(0.12, RaidCaptureMath.finalChance(0.08, 0.02, 0.02, 5.0, 5.0), 1.0e-9);
    }

    @Test
    @DisplayName("a press on the very first pass scores against the real, unwrapped center")
    void firstPassOffsetIsUnwrapped() {
        assertEquals(0L, RaidCaptureMath.offsetFromNearestCenter(500L, 0L, 1000));
        assertEquals(-500L, RaidCaptureMath.offsetFromNearestCenter(0L, 0L, 1000));
        assertEquals(499L, RaidCaptureMath.offsetFromNearestCenter(999L, 0L, 1000));
    }

    @Test
    @DisplayName("a press on a later pass scores against that pass's own center, not absolute elapsed time")
    void laterPassOffsetWrapsToItsOwnCenter() {
        // travelDurationMs=1000: centers recur at 500, 1500, 2500, 3500...
        assertEquals(0L, RaidCaptureMath.offsetFromNearestCenter(1_500L, 0L, 1000));
        assertEquals(0L, RaidCaptureMath.offsetFromNearestCenter(2_500L, 0L, 1000));
        assertEquals(0L, RaidCaptureMath.offsetFromNearestCenter(9_500L, 0L, 1000));
    }

    @Test
    @DisplayName("wrapping never produces the huge unbounded offset absolute elapsed time would")
    void wrappedOffsetStaysWithinOnePass() {
        long offset = RaidCaptureMath.offsetFromNearestCenter(47_318L, 0L, 1000);

        assertTrue(Math.abs(offset) <= 500L,
                "a press this late must still land within one pass's own half-width, not score a guaranteed miss");
    }
}
