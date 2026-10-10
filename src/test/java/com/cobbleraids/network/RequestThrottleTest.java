package com.cobbleraids.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestThrottleTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    @DisplayName("allows the budget within a window, then refuses the rest")
    void budgetThenRefuse() {
        RequestThrottle throttle = new RequestThrottle(3, 1000);
        assertTrue(throttle.allow(A, 0));
        assertTrue(throttle.allow(A, 10));
        assertTrue(throttle.allow(A, 20));
        assertFalse(throttle.allow(A, 30));
        assertFalse(throttle.allow(A, 999));
    }

    @Test
    @DisplayName("the budget refills when the window has passed")
    void refills() {
        RequestThrottle throttle = new RequestThrottle(1, 1000);
        assertTrue(throttle.allow(A, 0));
        assertFalse(throttle.allow(A, 500));
        assertTrue(throttle.allow(A, 1000));
    }

    @Test
    @DisplayName("one player's flood does not consume another's budget")
    void playersAreIndependent() {
        RequestThrottle throttle = new RequestThrottle(1, 1000);
        assertTrue(throttle.allow(A, 0));
        assertFalse(throttle.allow(A, 1));
        assertTrue(throttle.allow(B, 1));
    }

    @Test
    @DisplayName("forget gives a reconnecting player a fresh budget")
    void forgetResets() {
        RequestThrottle throttle = new RequestThrottle(1, 1000);
        assertTrue(throttle.allow(A, 0));
        assertFalse(throttle.allow(A, 1));
        throttle.forget(A);
        assertTrue(throttle.allow(A, 2));
    }

    @Test
    @DisplayName("a clock that goes backwards never locks a player out")
    void backwardsClock() {
        RequestThrottle throttle = new RequestThrottle(1, 1000);
        assertTrue(throttle.allow(A, 5000));
        assertTrue(throttle.allow(A, 100));
    }
}
