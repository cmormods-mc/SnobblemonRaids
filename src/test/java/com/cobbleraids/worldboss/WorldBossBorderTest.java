package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldBossBorderTest {

    private static double distance(double[] column, double px, double pz) {
        return Math.hypot(column[0] - px, column[1] - pz);
    }

    @Test
    void aPlayerFarFromTheCircleSeesNothing() {
        assertTrue(WorldBossBorder.columns(0, 0, 128, 0, 0, 40, 4).isEmpty(), "standing at the centre, 128 blocks from the wall");
        assertTrue(WorldBossBorder.columns(0, 0, 128, 60, 0, 40, 4).isEmpty(), "68 blocks inside");
        assertTrue(WorldBossBorder.columns(0, 0, 128, 400, 0, 40, 4).isEmpty(), "far outside");
    }

    @Test
    void everyColumnIsOnTheCircleAndWithinViewOfThePlayer() {
        for (double[] player : new double[][] {{120, 0}, {136, 0}, {0, 130}, {-90, -95}, {128, 0}}) {
            var columns = WorldBossBorder.columns(10, -20, 128, 10 + player[0], -20 + player[1], 40, 4);
            assertFalse(columns.isEmpty(), "near the wall at " + player[0] + "," + player[1]);
            for (double[] column : columns) {
                assertEquals(128.0, Math.hypot(column[0] - 10, column[1] + 20), 1e-6, "on the circle");
                assertTrue(distance(column, 10 + player[0], -20 + player[1]) <= 40.0 + 1e-6, "within view");
            }
        }
    }

    @Test
    void theWallReachesTheWholeViewOnBothSides() {
        var columns = WorldBossBorder.columns(0, 0, 128, 128, 0, 40, 4);
        double top = columns.stream().mapToDouble(c -> c[1]).max().orElse(0);
        double bottom = columns.stream().mapToDouble(c -> c[1]).min().orElse(0);
        assertTrue(top > 30 && bottom < -30, "about 40 blocks each way along the wall: " + bottom + " to " + top);
        assertEquals(columns.size(), columns.stream().map(c -> c[0] + "," + c[1]).distinct().count());
    }

    @Test
    void columnsSitOnAFixedGridSoTheWallDoesNotShimmerAsAPlayerWalks() {
        var first = WorldBossBorder.columns(0, 0, 128, 128, 0, 40, 4);
        var later = WorldBossBorder.columns(0, 0, 128, 127.9, 1.5, 40, 4);
        for (double[] column : later) {
            boolean shared = first.stream().anyMatch(other -> Math.hypot(other[0] - column[0], other[1] - column[1]) < 1e-9);
            double away = distance(column, 128, 0);
            if (away < 30) assertTrue(shared, "a column the player can see in both passes is in the same place");
        }
    }

    @Test
    void columnsAreSpacedAboutTheRequestedGap() {
        var columns = WorldBossBorder.columns(0, 0, 128, 128, 0, 40, 4);
        for (int i = 1; i < columns.size(); i++) {
            double gap = Math.hypot(columns.get(i)[0] - columns.get(i - 1)[0], columns.get(i)[1] - columns.get(i - 1)[1]);
            assertEquals(4.0, gap, 0.05);
        }
    }

    @Test
    void aTinyZoneIsDrawnAllTheWayRoundForSomeoneInsideIt() {
        var columns = WorldBossBorder.columns(0, 0, 20, 0, 0, 40, 4);
        assertTrue(columns.size() >= 30 && columns.size() <= WorldBossBorder.MAX_COLUMNS, "a circle of 20 is 31 columns: " + columns.size());
    }

    @Test
    void neverMoreThanTheCapIsSentInOnePass() {
        var columns = WorldBossBorder.columns(0, 0, 5000, 5000, 0, 96, 0.5);
        assertTrue(columns.size() <= WorldBossBorder.MAX_COLUMNS);
        assertFalse(columns.isEmpty());
        for (double[] column : columns) assertEquals(5000.0, Math.hypot(column[0], column[1]), 1e-6);
    }

    @Test
    void badInputGivesNothing() {
        assertTrue(WorldBossBorder.columns(0, 0, 0, 0, 0, 40, 4).isEmpty());
        assertTrue(WorldBossBorder.columns(0, 0, 128, 128, 0, 0, 4).isEmpty());
        assertTrue(WorldBossBorder.columns(0, 0, 128, 128, 0, 40, 0).isEmpty());
        assertTrue(WorldBossBorder.columns(0, 0, -5, 128, 0, 40, 4).isEmpty());
    }

    @Test
    void insideMatchesTheDuelRangeTest() {
        assertTrue(WorldBossBorder.inside(0, 0, 128, 128, 0), "the boundary counts as inside");
        assertTrue(WorldBossBorder.inside(0, 0, 128, 0, 0));
        assertFalse(WorldBossBorder.inside(0, 0, 128, 128.01, 0));
        assertTrue(WorldBossBorder.inside(100, 100, 50, 100 + 35, 100 + 35));
        assertFalse(WorldBossBorder.inside(100, 100, 50, 100 + 36, 100 + 36));
    }

    @Test
    void theColumnsAreAList() {
        List<double[]> columns = WorldBossBorder.columns(0, 0, 128, 128, 0, 40, 4);
        assertTrue(columns.size() > 5);
    }
}
