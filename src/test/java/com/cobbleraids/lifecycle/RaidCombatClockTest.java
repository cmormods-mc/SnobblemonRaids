package com.cobbleraids.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The raid combat timer. Ported from the standalone javac harness that used to live in
 * validation/combatclocktest, which nothing ran any more.
 *
 * <p>The behaviour worth pinning is that expiry is edge-triggered: RaidCombatRuleService calls
 * tick() every server tick and finalizes the raid the one time it returns true, so a clock that
 * kept returning true would try to time the same raid out on every tick after its limit.
 */
class RaidCombatClockTest {

    @Test
    @DisplayName("a limit of 0 means unlimited and never expires")
    void zeroIsUnlimited() {
        RaidCombatClock clock = new RaidCombatClock(0);

        assertFalse(clock.isTimed());
        assertEquals(-1, clock.getRemainingTicks(), "unlimited must report -1, not 0");
        for (int i = 0; i < 100; i++) {
            assertFalse(clock.tick(), "an unlimited clock expired on tick " + i);
        }
    }

    @Test
    @DisplayName("seconds are converted to ticks")
    void secondsBecomeTicks() {
        assertEquals(20, new RaidCombatClock(1).getLimitTicks());
        assertEquals(18_000, new RaidCombatClock(900).getLimitTicks());
    }

    @Test
    @DisplayName("expiry fires exactly on the limit tick, not before")
    void expiresOnTheLimitTick() {
        RaidCombatClock clock = new RaidCombatClock(1);

        for (int i = 1; i < 20; i++) {
            assertFalse(clock.tick(), "expired early on tick " + i);
        }
        assertEquals(1, clock.getRemainingTicks());
        assertTrue(clock.tick(), "did not expire on tick 20");
        assertEquals(0, clock.getRemainingTicks());
    }

    @Test
    @DisplayName("expiry is edge-triggered, so a raid cannot be timed out twice")
    void expiryIsEdgeTriggered() {
        RaidCombatClock clock = new RaidCombatClock(1);
        while (!clock.tick()) { /* run to expiry */ }

        for (int i = 0; i < 40; i++) {
            assertFalse(clock.tick(), "expiry repeated after the limit");
        }
        assertEquals(20, clock.getElapsedTicks(), "elapsed must stop at the limit");
    }

    @Test
    @DisplayName("elapsed and remaining stay consistent while running")
    void elapsedAndRemainingAgree() {
        RaidCombatClock clock = new RaidCombatClock(2);

        for (int i = 0; i < 15; i++) clock.tick();

        assertEquals(15, clock.getElapsedTicks());
        assertEquals(clock.getLimitTicks() - 15, clock.getRemainingTicks());
    }

    @Test
    @DisplayName("a negative limit is rejected")
    void negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RaidCombatClock(-1));
    }

    @Test
    @DisplayName("a limit that would overflow the tick counter is rejected")
    void overflowingLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RaidCombatClock(Integer.MAX_VALUE));
    }
}
