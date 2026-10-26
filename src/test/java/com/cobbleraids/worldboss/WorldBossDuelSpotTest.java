package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldBossDuelSpotTest {

    @Test
    void noCandidateIsEverTheOwnSpotOrNearIt() {
        double[][] looks = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {0.3, -0.7}, {0, 0}, {Double.NaN, 1}, {Double.POSITIVE_INFINITY, 0}, {1e-12, 1e-12}};
        for (double[] look : looks) {
            for (double[] at : new double[][] {{0, 0}, {255.35, -83.26}, {-1.0e6, 4.0e5}}) {
                var spots = WorldBossDuelSpot.candidates(at[0], at[1], look[0], look[1]);
                assertFalse(spots.isEmpty());
                for (double[] spot : spots) {
                    assertTrue(Double.isFinite(spot[0]) && Double.isFinite(spot[1]), "finite");
                    double apart = Math.hypot(spot[0] - at[0], spot[1] - at[1]);
                    assertTrue(apart >= WorldBossDuelSpot.NEAREST - 1e-9, "at least 6 blocks away, was " + apart);
                }
            }
        }
    }

    @Test
    void theFirstChoiceIsStraightAheadAtTheNearestDistance() {
        var first = WorldBossDuelSpot.candidates(10, 20, 0, 1).get(0);
        assertEquals(10.0, first[0], 1e-9);
        assertEquals(26.0, first[1], 1e-9);
        var east = WorldBossDuelSpot.candidates(0, 0, 5, 0).get(0);
        assertEquals(6.0, east[0], 1e-9);
        assertEquals(0.0, east[1], 1e-9);
    }

    @Test
    void thereAreCandidatesAllAroundAndInWiderRings() {
        var spots = WorldBossDuelSpot.candidates(0, 0, 1, 0);
        assertEquals(24, spots.size());
        assertTrue(spots.stream().anyMatch(s -> s[0] < -5.9), "one behind the player");
        assertTrue(spots.stream().anyMatch(s -> Math.hypot(s[0], s[1]) > 9.9), "one in the outer ring");
        assertEquals(spots.size(), spots.stream().map(s -> Math.round(s[0] * 1000) + "," + Math.round(s[1] * 1000)).distinct().count(), "no duplicates");
    }

    @Test
    void aBadPlayerPositionGivesNoCandidatesRatherThanNaN() {
        assertTrue(WorldBossDuelSpot.candidates(Double.NaN, 0, 1, 0).isEmpty());
        assertTrue(WorldBossDuelSpot.candidates(0, Double.POSITIVE_INFINITY, 1, 0).isEmpty());
    }
}
