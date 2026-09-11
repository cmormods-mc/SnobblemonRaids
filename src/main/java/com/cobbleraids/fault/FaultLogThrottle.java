package com.cobbleraids.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides how often a repeating fault is allowed to reach the log.
 *
 * <p>A fault inside a server-tick subsystem does not happen once: it happens 20 times a second for
 * as long as the condition lasts, and the barrier that keeps it off the main thread is exactly what
 * lets it repeat forever. Printing every occurrence would turn one contained bug into a filled disk,
 * so each distinct key logs its first occurrence immediately and then at most once per window,
 * carrying the number suppressed in between -- which is also the number an operator needs to tell a
 * one-off from a hot loop.
 *
 * <p>Kept free of Minecraft types so it can be unit tested; the clock is injectable for the same
 * reason. Safe to call from any thread, because datapack loading prepares off the server thread.
 */
public final class FaultLogThrottle {
    /** Long enough that a per-tick fault costs one line a minute, short enough to still show a trend. */
    public static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /** Returned by {@link #record} when the occurrence should not be logged at all. */
    public static final long SUPPRESS = -1L;

    private final Map<String, State> byKey = new ConcurrentHashMap<>();
    private final long windowMillis;

    public FaultLogThrottle() { this(DEFAULT_WINDOW_MILLIS); }

    public FaultLogThrottle(long windowMillis) {
        if (windowMillis < 0) throw new IllegalArgumentException("windowMillis must not be negative");
        this.windowMillis = windowMillis;
    }

    /**
     * Records one occurrence of {@code key} at {@code nowMillis}.
     *
     * @return {@link #SUPPRESS} to stay silent, otherwise the number of occurrences suppressed since
     *         the previous logged one (0 on the first occurrence, and whenever nothing was skipped).
     */
    public long record(String key, long nowMillis) {
        // compute() so the read-decide-write is atomic per key: two threads faulting on the same key
        // must not both decide they are the one allowed to log.
        State updated = byKey.compute(key, (ignored, state) -> {
            if (state == null || nowMillis - state.lastLoggedAtMillis >= windowMillis) {
                return State.logging(nowMillis, state == null ? 0L : state.suppressed);
            }
            return state.suppressing();
        });
        return updated.loggingNow ? updated.reportedSuppressed : SUPPRESS;
    }

    /** Forgets every key, so a fault that stops and later returns is reported immediately again. */
    public void clear() { byKey.clear(); }

    /** Number of distinct keys currently held; the map is bounded by the caller's key vocabulary. */
    public int trackedKeys() { return byKey.size(); }

    private record State(long lastLoggedAtMillis, long suppressed, boolean loggingNow, long reportedSuppressed) {
        static State logging(long nowMillis, long suppressed) {
            return new State(nowMillis, 0L, true, suppressed);
        }

        State suppressing() {
            return new State(lastLoggedAtMillis, suppressed + 1L, false, 0L);
        }
    }
}
