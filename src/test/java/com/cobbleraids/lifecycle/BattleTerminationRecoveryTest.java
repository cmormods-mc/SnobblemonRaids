package com.cobbleraids.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BattleTerminationRecoveryTest {
    @Test
    void successfulEndRunsOnceWithoutFallback() {
        var registered = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        BattleTerminationRecovery.finish(() -> false, () -> {
            calls.incrementAndGet();
            registered.set(false);
        }, registered::get, () -> fail("normal termination must not be replaced"));
        assertEquals(1, calls.get());
    }

    @Test
    void failingEndRetiresItsStaleEntryWithoutReplayingSideEffects() {
        var ended = new AtomicBoolean();
        var registered = new AtomicBoolean(true);
        var rewards = new AtomicInteger();
        var original = new IllegalStateException("end callback failed after progression");
        Runnable end = () -> {
            ended.set(true);
            rewards.incrementAndGet();
            throw original;
        };
        assertSame(original, assertThrows(IllegalStateException.class, () ->
                BattleTerminationRecovery.finish(ended::get, end, registered::get, () -> registered.set(false))));
        assertFalse(registered.get());
        BattleTerminationRecovery.finish(ended::get, end, registered::get, () -> fail("already retired"));
        assertEquals(1, rewards.get(), "partially completed progression is never replayed");
    }

    @Test
    void alreadyEndedButRegisteredBattleStillGetsRetired() {
        var retired = new AtomicBoolean();
        BattleTerminationRecovery.finish(() -> true, () -> fail("end must not be called twice"),
                () -> true, () -> retired.set(true));
        assertTrue(retired.get());
    }

    @Test
    void failureAfterRegistryRemovalDoesNotReplayCloseHandlers() {
        var original = new IllegalArgumentException("Showdown close failed after map removal");
        assertSame(original, assertThrows(IllegalArgumentException.class, () ->
                BattleTerminationRecovery.finish(() -> false, () -> { throw original; },
                        () -> false, () -> fail("already removed from Cobblemon"))));
    }

    @Test
    void fallbackFailureKeepsTheOriginalFailureAndLaterCleanupStillRuns() {
        var original = new IllegalStateException("end failed");
        var recovery = new IllegalArgumentException("retirement failed");
        var bossReleased = new AtomicBoolean();
        var guard = new RaidFinalizationGuard();
        var caught = assertThrows(IllegalStateException.class, () -> guard.finalizeOnce(java.util.UUID.randomUUID(),
                () -> {},
                () -> BattleTerminationRecovery.finish(() -> false, () -> { throw original; },
                        () -> true, () -> { throw recovery; }),
                () -> bossReleased.set(true)));
        assertSame(original, caught);
        assertArrayEquals(new Throwable[]{recovery}, caught.getSuppressed());
        assertTrue(bossReleased.get());
        assertEquals(0, guard.size());
    }
}
