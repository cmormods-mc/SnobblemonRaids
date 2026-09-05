package com.cmormods.rankstacklimits.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StackLimitPolicyTest {
    @Test
    void missingMetaFallsBackToDefault() {
        assertEquals(64, StackLimitPolicy.resolve(null, 64, 99));
        assertEquals(64, StackLimitPolicy.resolve("   ", 64, 99));
    }

    @Test
    void invalidMetaFallsBackToDefault() {
        assertEquals(64, StackLimitPolicy.resolve("vip", 64, 99));
    }

    @Test
    void valuesBelowDefaultCannotReduceTheVanillaLimit() {
        assertEquals(64, StackLimitPolicy.resolve("32", 64, 99));
    }

    @Test
    void validRankValueIsPreserved() {
        assertEquals(80, StackLimitPolicy.resolve("80", 64, 99));
        assertEquals(96, StackLimitPolicy.resolve(" 96 ", 64, 99));
    }

    @Test
    void valuesAboveConfiguredMaximumAreClamped() {
        assertEquals(99, StackLimitPolicy.resolve("100", 64, 99));
        assertEquals(90, StackLimitPolicy.resolve("99", 64, 90));
    }
}
