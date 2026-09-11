package com.cobbleraids.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The rate limit behind RaidFaultBarrier. This is not a cosmetic concern: the barrier's whole
 * purpose is to let a fault repeat instead of ending the server, so a fault inside a tick subsystem
 * recurs 20 times a second indefinitely. Without a working throttle the fix for "the server stops"
 * would be "the disk fills", which on an unattended box is the same outage with extra steps.
 */
class FaultLogThrottleTest {

    private static final String KEY = "spawning";

    @Test
    @DisplayName("the first occurrence of a key always logs, with nothing suppressed")
    void firstOccurrenceLogs() {
        FaultLogThrottle throttle = new FaultLogThrottle(60_000L);

        assertEquals(0L, throttle.record(KEY, 1_000L));
    }

    @Test
    @DisplayName("occurrences inside the window are suppressed and counted, not logged")
    void insideWindowIsSuppressed() {
        FaultLogThrottle throttle = new FaultLogThrottle(60_000L);
        throttle.record(KEY, 0L);

        for (long now = 1L; now < 60_000L; now += 50L) {
            assertEquals(FaultLogThrottle.SUPPRESS, throttle.record(KEY, now),
                    "logged again at " + now + "ms, inside the 60s window");
        }
        // 1..59_951 stepping by 50 is 1200 occurrences: one minute of a fault thrown every tick.
        assertEquals(1200L, throttle.record(KEY, 60_000L), "the suppressed count must survive to the next line");
    }

    @Test
    @DisplayName("the window is measured from the last logged line, not the first occurrence")
    void windowRestartsFromEachLoggedLine() {
        FaultLogThrottle throttle = new FaultLogThrottle(1_000L);
        assertEquals(0L, throttle.record(KEY, 0L));
        assertEquals(0L, throttle.record(KEY, 1_000L));

        assertEquals(FaultLogThrottle.SUPPRESS, throttle.record(KEY, 1_999L));
        assertEquals(1L, throttle.record(KEY, 2_000L));
    }

    @Test
    @DisplayName("the suppressed count resets once reported, so counts are per-line not cumulative")
    void suppressedCountResets() {
        FaultLogThrottle throttle = new FaultLogThrottle(1_000L);
        throttle.record(KEY, 0L);
        throttle.record(KEY, 100L);
        throttle.record(KEY, 200L);

        assertEquals(2L, throttle.record(KEY, 1_000L));
        assertEquals(0L, throttle.record(KEY, 2_000L), "the previous 2 must not be counted twice");
    }

    @Test
    @DisplayName("keys are independent, so one noisy subsystem cannot silence another")
    void keysAreIndependent() {
        FaultLogThrottle throttle = new FaultLogThrottle(60_000L);
        assertEquals(0L, throttle.record("spawning", 0L));
        for (int i = 1; i < 500; i++) throttle.record("spawning", i);

        assertEquals(0L, throttle.record("battle-victory", 400L),
                "a first fault in another subsystem was swallowed by the noisy one");
        assertEquals(2, throttle.trackedKeys());
    }

    @Test
    @DisplayName("clear() makes a returning fault report immediately again")
    void clearForgetsKeys() {
        FaultLogThrottle throttle = new FaultLogThrottle(60_000L);
        throttle.record(KEY, 0L);
        assertEquals(FaultLogThrottle.SUPPRESS, throttle.record(KEY, 10L));

        throttle.clear();

        assertEquals(0, throttle.trackedKeys());
        assertEquals(0L, throttle.record(KEY, 20L));
    }

    @Test
    @DisplayName("a zero window logs every occurrence, and a negative one is rejected")
    void windowBounds() {
        FaultLogThrottle unlimited = new FaultLogThrottle(0L);
        assertEquals(0L, unlimited.record(KEY, 0L));
        assertEquals(0L, unlimited.record(KEY, 0L));

        assertThrows(IllegalArgumentException.class, () -> new FaultLogThrottle(-1L));
    }

    @Test
    @DisplayName("concurrent recorders elect exactly one logger per window")
    void concurrentRecordersElectOneLogger() throws Exception {
        // Datapack loading prepares off the server thread, so report() is genuinely reachable from
        // more than one thread. Two threads both deciding they are the one allowed to log would be a
        // read-decide-write race in record().
        FaultLogThrottle throttle = new FaultLogThrottle(60_000L);
        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger logged = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (throttle.record(KEY, 5_000L) != FaultLogThrottle.SUPPRESS) logged.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "recorder threads did not finish");

        assertEquals(1, logged.get(), "every occurrence shared one timestamp and one window, so exactly one may log");
    }
}
