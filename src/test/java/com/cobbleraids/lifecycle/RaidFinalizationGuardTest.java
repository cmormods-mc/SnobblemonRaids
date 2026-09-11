package com.cobbleraids.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The rule that stops a contained fault from stranding a raid forever.
 *
 * <p>This exists because of a bug the fault barriers introduced. Before them, a throw halfway
 * through finalization crashed the server -- bad, but loud, and a restart reset everything.
 * Afterwards the barrier caught it and the raid was stranded for good: the claim was never released
 * so every later attempt returned immediately, the session stayed in RaidRegistry, the boss was
 * never discarded, and its raid slot stayed held for the life of the server. Containment had turned
 * a crash into silent corruption.
 *
 * <p>So the property under test is not "finalization runs once". It is "cleanup runs even when the
 * rest of finalization does not", which is the part that was missing.
 */
class RaidFinalizationGuardTest {

    private final RaidFinalizationGuard guard = new RaidFinalizationGuard();
    private final UUID raid = UUID.randomUUID();

    @Test
    @DisplayName("a normal finalization runs side effects then cleanup, in that order")
    void happyPath() {
        List<String> order = new ArrayList<>();

        assertTrue(guard.finalizeOnce(raid, () -> order.add("effects"), () -> order.add("cleanup")));

        assertEquals(List.of("effects", "cleanup"), order);
        assertFalse(guard.isFinalizing(raid));
        assertEquals(0, guard.size());
    }

    @Test
    @DisplayName("cleanup still runs when a side effect throws")
    void cleanupRunsOnFailure() {
        AtomicInteger cleanups = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> guard.finalizeOnce(raid,
                () -> { throw new IllegalStateException("reward persistence failed"); },
                cleanups::incrementAndGet));

        assertEquals(1, cleanups.get(),
                "the registry entry, the boss and the raid slot must be released however the rest went");
    }

    @Test
    @DisplayName("the claim is released when a side effect throws, so the raid is not stranded")
    void claimReleasedOnFailure() {
        assertThrows(RuntimeException.class, () -> guard.finalizeOnce(raid,
                () -> { throw new RuntimeException("boom"); }, () -> {}));

        assertFalse(guard.isFinalizing(raid), "a leaked claim makes the raid permanently unfinalizable");
        assertEquals(0, guard.size());
    }

    @Test
    @DisplayName("the claim is released even when cleanup itself throws")
    void claimReleasedWhenCleanupThrows() {
        // A leaked claim is the worst outcome available: unlike the boss entity or the registry
        // entry, nothing else in the system would ever release it.
        assertThrows(IllegalStateException.class, () -> guard.finalizeOnce(raid,
                () -> {}, () -> { throw new IllegalStateException("discard failed"); }));

        assertFalse(guard.isFinalizing(raid));
    }

    @Test
    @DisplayName("the exception propagates after cleanup, so the caller's barrier still reports it")
    void exceptionPropagates() {
        RuntimeException thrown = new RuntimeException("the original cause");

        RuntimeException caught = assertThrows(RuntimeException.class,
                () -> guard.finalizeOnce(raid, () -> { throw thrown; }, () -> {}));

        assertEquals(thrown, caught,
                "swallowing it here would make a broken raid completely silent");
    }

    @Test
    @DisplayName("a second finalization of the same raid does nothing while one is in flight")
    void reentrantCallIsRefused() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();

        guard.finalizeOnce(raid, () -> {
            // Four paths can end the same raid; a re-entrant call from inside one must be refused.
            assertFalse(guard.finalizeOnce(raid, effects::incrementAndGet, cleanups::incrementAndGet));
            effects.incrementAndGet();
        }, cleanups::incrementAndGet);

        assertEquals(1, effects.get(), "the nested call must not have run its side effects");
        assertEquals(1, cleanups.get(), "nor its cleanup");
    }

    @Test
    @DisplayName("a raid can be finalized again after a failed attempt released the claim")
    void retryIsPossibleAfterFailure() {
        assertThrows(RuntimeException.class, () -> guard.finalizeOnce(raid,
                () -> { throw new RuntimeException("first attempt"); }, () -> {}));

        AtomicInteger effects = new AtomicInteger();
        assertTrue(guard.finalizeOnce(raid, effects::incrementAndGet, () -> {}),
                "the claim was released, so a later terminal path may still close the raid out");
        assertEquals(1, effects.get());
    }

    @Test
    @DisplayName("different raids do not block each other")
    void raidsAreIndependent() {
        UUID other = UUID.randomUUID();

        guard.finalizeOnce(raid, () -> {
            assertTrue(guard.isFinalizing(raid));
            assertFalse(guard.isFinalizing(other));
        }, () -> {});

        AtomicInteger effects = new AtomicInteger();
        assertTrue(guard.finalizeOnce(other, effects::incrementAndGet, () -> {}));
        assertEquals(1, effects.get());
    }

    @Test
    @DisplayName("concurrent terminal paths: only one is admitted while a finalization is in flight")
    void concurrentPathsElectOneWhileInFlight() throws Exception {
        // Damage is interpreted off the server thread, so a pool hitting zero can race the combat
        // timer expiring on the tick. Exactly one may be inside finalization at a time.
        //
        // Note what this does NOT claim. The claim is released when finalization ends, on purpose,
        // so a later terminal path can still close out a raid whose first attempt failed. What stops
        // a *completed* raid being finalized twice is the cleanup removing it from RaidRegistry, so
        // a second battle event can no longer resolve back to it -- not this guard.
        int contenders = 7;
        CountDownLatch winnerInside = new CountDownLatch(1);
        CountDownLatch losersDone = new CountDownLatch(contenders);
        CountDownLatch allFinished = new CountDownLatch(contenders + 1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();

        Thread winner = new Thread(() -> {
            try {
                if (guard.finalizeOnce(raid, () -> {
                    effects.incrementAndGet();
                    winnerInside.countDown();
                    try {
                        // Stay inside finalization until every other path has had its turn.
                        losersDone.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }, cleanups::incrementAndGet)) {
                    admitted.incrementAndGet();
                }
            } finally {
                allFinished.countDown();
            }
        });
        winner.setDaemon(true);
        winner.start();
        assertTrue(winnerInside.await(20, TimeUnit.SECONDS), "no thread entered finalization");

        for (int i = 0; i < contenders; i++) {
            Thread loser = new Thread(() -> {
                try {
                    if (guard.finalizeOnce(raid, effects::incrementAndGet, cleanups::incrementAndGet)) {
                        admitted.incrementAndGet();
                    }
                } finally {
                    losersDone.countDown();
                    allFinished.countDown();
                }
            });
            loser.setDaemon(true);
            loser.start();
        }

        assertTrue(allFinished.await(30, TimeUnit.SECONDS), "finalizer threads did not finish");
        assertEquals(1, admitted.get(), "exactly one path may be inside finalization");
        assertEquals(1, effects.get());
        assertEquals(1, cleanups.get(), "the boss must not be discarded eight times");
    }

    @Test
    @DisplayName("clear drops claims left by raids still in flight at shutdown")
    void clearReleasesInFlightClaims() {
        RaidFinalizationGuard stuck = new RaidFinalizationGuard();
        // Simulate a shutdown landing mid-finalization by claiming and never completing.
        assertThrows(RuntimeException.class, () -> stuck.finalizeOnce(raid,
                () -> { throw new RuntimeException("interrupted by shutdown"); },
                () -> { throw new RuntimeException("cleanup also failed"); }));

        stuck.clear();
        assertEquals(0, stuck.size());
    }
}
