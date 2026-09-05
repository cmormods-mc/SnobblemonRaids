package com.cmormods.rankstacklimits.policy;

public final class StackLimitPolicy {
    private StackLimitPolicy() {
    }

    public static int resolve(String rawMetaValue, int defaultLimit, int maximumLimit) {
        if (rawMetaValue == null || rawMetaValue.isBlank()) {
            return defaultLimit;
        }

        final int parsed;
        try {
            parsed = Integer.parseInt(rawMetaValue.trim());
        } catch (NumberFormatException ignored) {
            return defaultLimit;
        }

        if (parsed < defaultLimit) {
            return defaultLimit;
        }
        return Math.min(parsed, maximumLimit);
    }
}
