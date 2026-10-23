package com.cobbleraids.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting behind the release-day performance log. A wrong total here would not break a raid,
 * it would send the operator chasing the wrong subsystem on the one day the numbers matter, so the
 * window/lifetime split and the CSV shape are pinned.
 */
class RaidPerfTest {

    @AfterEach
    void clear() {
        RaidPerf.onServerStopped();
    }

    @Test
    @DisplayName("a run is counted and its time lands in both the window and the lifetime totals")
    void recordsRuns() {
        RaidPerf.stop("alpha", RaidPerf.start() - 2_000_000L);
        RaidPerf.stop("alpha", RaidPerf.start() - 4_000_000L);

        RaidPerf.Row window = RaidPerf.window(false).get(0);
        assertEquals("alpha", window.name());
        assertEquals(2L, window.count());
        assertTrue(window.totalMillis() >= 6.0, "two runs of 2 and 4 ms add to at least 6 ms");
        assertTrue(window.maxMillis() >= 4.0 && window.maxMillis() < window.totalMillis());
        assertEquals(2L, RaidPerf.lifetime().get(0).count());
    }

    @Test
    @DisplayName("resetting the window leaves the lifetime totals alone")
    void windowResetKeepsLifetime() {
        RaidPerf.stop("beta", RaidPerf.start() - 1_000_000L);
        assertEquals(1L, RaidPerf.window(true).get(0).count());

        assertTrue(RaidPerf.window(false).isEmpty(), "a section with no runs in the window is not listed");
        assertEquals(1L, RaidPerf.lifetime().get(0).count());

        RaidPerf.stop("beta", RaidPerf.start());
        assertEquals(1L, RaidPerf.window(false).get(0).count());
        assertEquals(2L, RaidPerf.lifetime().get(0).count());
    }

    @Test
    @DisplayName("rows come back busiest first, with ties broken by name")
    void ordering() {
        RaidPerf.stop("small", RaidPerf.start() - 1_000_000L);
        RaidPerf.stop("big", RaidPerf.start() - 9_000_000L);

        List<RaidPerf.Row> rows = RaidPerf.lifetime();
        assertEquals("big", rows.get(0).name());
        assertEquals("small", rows.get(1).name());
    }

    @Test
    @DisplayName("slow-run thresholds tighten with how often a section runs, and fall back to a whole tick")
    void thresholds() {
        assertEquals(10L, RaidPerf.slowThresholdMillis("spawning"));
        assertEquals(5L, RaidPerf.slowThresholdMillis("mixin:selectActions"));
        assertEquals(40L, RaidPerf.slowThresholdMillis("web:icon-first-load"));
        assertEquals(20L, RaidPerf.slowThresholdMillis("web:cobbleraids.shop"));
        assertEquals(25L, RaidPerf.slowThresholdMillis("flush:rewards"));
        assertEquals(15L, RaidPerf.slowThresholdMillis("spawn:attempt"));
        assertEquals(50L, RaidPerf.slowThresholdMillis("something-new"));
    }

    @Test
    @DisplayName("count() records an event with no duration")
    void countsEvents() {
        RaidPerf.count("opened");
        RaidPerf.count("opened");

        RaidPerf.Row row = RaidPerf.lifetime().get(0);
        assertEquals(2L, row.count());
        assertEquals(0L, row.totalNanos());
    }

    @Test
    @DisplayName("CSV rows have the header's eight columns and use a dot for decimals whatever the locale")
    void csvShape() {
        int columns = RaidPerf.CSV_HEADER.split(",", -1).length;
        RaidPerf.stop("gamma", RaidPerf.start() - 1_500_000L);

        String row = RaidPerf.csvLines("2026-10-24T20:00:00Z", RaidPerf.lifetime()).get(0);
        String gauge = RaidPerf.gaugeLine("2026-10-24T20:00:00Z", "players", 27);

        assertEquals(columns, row.split(",", -1).length);
        assertEquals(columns, gauge.split(",", -1).length);
        assertTrue(row.startsWith("2026-10-24T20:00:00Z,section,gamma,1,"));
        assertTrue(gauge.endsWith(",27.00"));
    }
}
