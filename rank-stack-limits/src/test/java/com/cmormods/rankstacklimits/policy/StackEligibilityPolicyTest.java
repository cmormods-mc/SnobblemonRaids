package com.cmormods.rankstacklimits.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StackEligibilityPolicyTest {
    @Test
    void vanillaUnstackablesRemainOneWhenProtectionIsEnabled() {
        assertEquals(1, StackEligibilityPolicy.effectiveLimit(1, 99, true));
    }

    @Test
    void protectionCanBeExplicitlyDisabled() {
        assertEquals(99, StackEligibilityPolicy.effectiveLimit(1, 99, false));
    }

    @Test
    void ordinaryStackableItemsGainThePlayersRankLimit() {
        assertEquals(80, StackEligibilityPolicy.effectiveLimit(64, 80, true));
        assertEquals(96, StackEligibilityPolicy.effectiveLimit(16, 96, true));
    }

    @Test
    void existingHigherIntrinsicLimitIsNeverReduced() {
        assertEquals(99, StackEligibilityPolicy.effectiveLimit(99, 80, true));
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StackEligibilityPolicy.effectiveLimit(0, 64, true));
        assertThrows(IllegalArgumentException.class, () -> StackEligibilityPolicy.effectiveLimit(64, 0, true));
    }
}
