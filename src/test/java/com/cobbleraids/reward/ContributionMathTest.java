package com.cobbleraids.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.reward.ContributionMath.Threshold;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContributionMathTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    @DisplayName("shares of eligible players sum to exactly 100%")
    void sharesSumToOneHundred() {
        Map<String, Double> percentages = ContributionMath.percentages(
                Map.of("a", 300.0, "b", 100.0, "c", 100.0), List.of("a", "b", "c"));

        assertEquals(60.0, percentages.get("a"), EPSILON);
        assertEquals(20.0, percentages.get("b"), EPSILON);
        assertEquals(20.0, percentages.get("c"), EPSILON);
        assertEquals(100.0, percentages.values().stream().mapToDouble(Double::doubleValue).sum(), EPSILON);
    }

    @Test
    @DisplayName("damage from an ineligible player is excluded from the denominator")
    void ineligibleDamageDoesNotDiluteTheShare() {
        // A player who withdrew or disconnected keeps their recorded damage but forfeits rewards.
        // If that damage still counted toward the total, everyone left would be under-credited.
        Map<String, Double> percentages = ContributionMath.percentages(
                Map.of("stayed", 100.0, "withdrew", 900.0), List.of("stayed"));

        assertEquals(100.0, percentages.get("stayed"), EPSILON);
    }

    @Test
    @DisplayName("a zero-damage victory credits everyone 0% rather than dividing by zero")
    void zeroTotalIsNotNaN() {
        Map<String, Double> percentages = ContributionMath.percentages(
                Map.of("a", 0.0, "b", 0.0), List.of("a", "b"));

        for (double value : percentages.values()) {
            assertTrue(Double.isFinite(value), "expected a finite share, got " + value);
            assertEquals(0.0, value, EPSILON);
        }
    }

    @Test
    @DisplayName("negative and missing damage entries are floored at zero")
    void negativeAndMissingDamageAreFloored() {
        Map<String, Double> percentages = ContributionMath.percentages(
                Map.of("a", 50.0, "b", -1000.0), List.of("a", "b", "never-recorded"));

        assertEquals(100.0, percentages.get("a"), EPSILON);
        assertEquals(0.0, percentages.get("b"), EPSILON);
        assertEquals(0.0, percentages.get("never-recorded"), EPSILON);
    }

    @Test
    @DisplayName("eligible players are all present in the result, damage recorded or not")
    void everyEligiblePlayerIsPresent() {
        Map<String, Double> percentages = ContributionMath.percentages(
                Map.of("a", 10.0), List.of("a", "b"));

        assertEquals(2, percentages.size());
        assertTrue(percentages.containsKey("b"));
    }

    @Test
    @DisplayName("the result is unmodifiable, so a caller cannot corrupt a reward tally")
    void resultIsUnmodifiable() {
        Map<String, Double> percentages = ContributionMath.percentages(Map.of("a", 1.0), List.of("a"));
        assertThrows(UnsupportedOperationException.class, () -> percentages.put("b", 50.0));
    }

    @Test
    @DisplayName("the highest threshold at or below the share wins, unsorted input included")
    void highestMatchingThresholdWins() {
        List<Threshold> thresholds = List.of(
                new Threshold(50.0, 2), new Threshold(10.0, 1), new Threshold(75.0, 3));

        assertEquals(0, ContributionMath.bonusRolls(9.0, thresholds));
        assertEquals(1, ContributionMath.bonusRolls(10.0, thresholds));
        assertEquals(1, ContributionMath.bonusRolls(49.9, thresholds));
        assertEquals(2, ContributionMath.bonusRolls(50.0, thresholds));
        assertEquals(3, ContributionMath.bonusRolls(100.0, thresholds));
    }

    @Test
    @DisplayName("a solo raider's 100% share is not lost to floating-point drift")
    void thresholdComparisonToleratesFloatingPointDrift() {
        // 100.0 is computed as (value / total) * 100.0, so a solo raider can land a hair under a
        // 100.0 threshold. Losing the top reward tier to the last bit of a double would be a
        // maddening bug to report and an easy one to introduce.
        double soloShare = ContributionMath.percentages(Map.of("a", 7.0), List.of("a")).get("a");

        assertEquals(1, ContributionMath.bonusRolls(soloShare, List.of(new Threshold(100.0, 1))));
    }

    @Test
    @DisplayName("no thresholds configured means no bonus rolls")
    void noThresholdsMeansNoRolls() {
        assertEquals(0, ContributionMath.bonusRolls(100.0, List.of()));
    }
}
