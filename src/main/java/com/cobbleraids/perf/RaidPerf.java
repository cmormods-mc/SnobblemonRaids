package com.cobbleraids.perf;

import com.cobbleraids.fault.FaultLogThrottle;
import com.cobbleraids.RaidLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cheap, always-on timing for the places this mod could cost a busy server tick.
 *
 * <p>Two numbers per named section, kept for the current reporting window and for the life of the
 * server: how many times it ran and how long it took in total, plus the single slowest run. That is
 * enough to answer the two questions a release-day log has to: is anything here a steady cost
 * (total), and did anything here ever stall a tick (max).
 *
 * <p>The price of a measurement is two {@code System.nanoTime()} calls and one map lookup, so it is
 * safe to leave on in production and to put on the per-tick path. No lambda is allocated: callers
 * hold the start time in a local, the same reason {@code RaidFaultBarrier.safeTick} takes a method
 * reference.
 *
 * <p>Free of Minecraft types so the accounting can be unit tested. {@link RaidPerfReporter} owns
 * everything that needs a server.
 */
public final class RaidPerf {
    /** A section slower than this is worth a line in the log the moment it happens, not only in the summary. */
    private static final long DEFAULT_SLOW_MILLIS = 50L;

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static final FaultLogThrottle SLOW_LOG = new FaultLogThrottle();

    private RaidPerf() {}

    /** One section's totals. Synchronised because a few sections are timed from off the server thread. */
    private static final class Stat {
        private long lifetimeCount;
        private long lifetimeNanos;
        private long lifetimeMax;
        private long windowCount;
        private long windowNanos;
        private long windowMax;

        synchronized void add(long nanos) {
            lifetimeCount++;
            lifetimeNanos += nanos;
            if (nanos > lifetimeMax) lifetimeMax = nanos;
            windowCount++;
            windowNanos += nanos;
            if (nanos > windowMax) windowMax = nanos;
        }

        synchronized Row row(String name, boolean window, boolean reset) {
            Row row = window
                    ? new Row(name, windowCount, windowNanos, windowMax)
                    : new Row(name, lifetimeCount, lifetimeNanos, lifetimeMax);
            if (reset) {
                windowCount = 0L;
                windowNanos = 0L;
                windowMax = 0L;
            }
            return row;
        }
    }

    /** One section's totals for a window or for the whole run. */
    public record Row(String name, long count, long totalNanos, long maxNanos) {
        public double totalMillis() { return totalNanos / 1_000_000.0; }
        public double averageMillis() { return count == 0L ? 0.0 : totalNanos / 1_000_000.0 / count; }
        public double maxMillis() { return maxNanos / 1_000_000.0; }
    }

    /** Start of a measurement; pass the result to {@link #stop}. */
    public static long start() {
        return System.nanoTime();
    }

    /** Ends a measurement begun with {@link #start} and files it under {@code name}. */
    public static void stop(String name, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        STATS.computeIfAbsent(name, ignored -> new Stat()).add(elapsed);
        if (elapsed >= slowThresholdNanos(name)) logSlow(name, elapsed);
    }

    /** Counts an event that has no duration worth timing, e.g. a page opening. */
    public static void count(String name) {
        STATS.computeIfAbsent(name, ignored -> new Stat()).add(0L);
    }

    /**
     * How long a single run of {@code name} may take before it is logged on the spot.
     *
     * <p>One tick is 50 ms, so the default flags anything that on its own costs a whole tick. Some
     * sections deserve a tighter or a looser bar than that: a per-tick subsystem is called twenty
     * times a second and has no business being near a tick, while a flush to disk or a first-time
     * icon read is allowed to be slower because it happens rarely.
     */
    static long slowThresholdNanos(String name) {
        return slowThresholdMillis(name) * 1_000_000L;
    }

    /** Sections that run on every server tick; they share the 50 ms budget with the whole server. */
    private static final java.util.Set<String> PER_TICK = java.util.Set.of(
            "lobby", "spawning", "rewards", "capture-sessions", "reconnect-grace",
            "combat-timer", "boss-glow", "web-sounds", "consistency-audit", "perf-report");

    static long slowThresholdMillis(String name) {
        // A fifth of a tick: nine of these run back to back every tick, so one this slow is a problem.
        if (PER_TICK.contains(name)) return 10L;
        // Runs inside the packet path on every battle choice.
        if (name.startsWith("mixin:")) return 5L;
        if (name.endsWith("-first-load")) return 40L;
        if (name.startsWith("web:")) return 20L;
        if (name.startsWith("spawn:")) return 15L;
        // Disk writes and the full consistency sweep may be slower, but they are the ones to watch.
        if (name.startsWith("flush:") || name.startsWith("consistency-audit:")) return 25L;
        return DEFAULT_SLOW_MILLIS;
    }

    private static void logSlow(String name, long elapsedNanos) {
        long suppressed = SLOW_LOG.record(name, System.currentTimeMillis());
        if (suppressed == FaultLogThrottle.SUPPRESS) return;
        RaidLog.warn("[perf] {} took {} ms in a single run{}", name,
                String.format(Locale.ROOT, "%.1f", elapsedNanos / 1_000_000.0),
                suppressed > 0L ? " (" + suppressed + " more slow run(s) not logged since the last report)" : "");
    }

    /** The current window, busiest first. Pass {@code reset} to start the next window. */
    public static List<Row> window(boolean reset) {
        return rows(true, reset);
    }

    /** Everything since the server started, busiest first. */
    public static List<Row> lifetime() {
        return rows(false, false);
    }

    private static List<Row> rows(boolean window, boolean reset) {
        List<Row> rows = new ArrayList<>(STATS.size());
        for (Map.Entry<String, Stat> entry : STATS.entrySet()) {
            Row row = entry.getValue().row(entry.getKey(), window, reset);
            if (row.count() > 0L) rows.add(row);
        }
        rows.sort(Comparator.comparingLong(Row::totalNanos).reversed().thenComparing(Row::name));
        return rows;
    }

    /** One CSV line per row, for the release-day file. */
    public static List<String> csvLines(String isoTime, List<Row> rows) {
        List<String> lines = new ArrayList<>(rows.size());
        for (Row row : rows) {
            lines.add(String.format(Locale.ROOT, "%s,section,%s,%d,%.3f,%.4f,%.3f,",
                    isoTime, row.name(), row.count(), row.totalMillis(), row.averageMillis(), row.maxMillis()));
        }
        return lines;
    }

    /** One CSV line for a point-in-time reading such as players online. */
    public static String gaugeLine(String isoTime, String name, double value) {
        return String.format(Locale.ROOT, "%s,gauge,%s,,,,,%.2f", isoTime, name, value);
    }

    public static final String CSV_HEADER = "time,kind,name,count,total_ms,avg_ms,max_ms,value";

    /** Cleared with the rest of the per-server state; an integrated client reuses this JVM. */
    public static void onServerStopped() {
        STATS.clear();
        SLOW_LOG.clear();
    }
}
