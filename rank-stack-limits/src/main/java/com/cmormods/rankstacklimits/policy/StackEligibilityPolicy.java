package com.cmormods.rankstacklimits.policy;

public final class StackEligibilityPolicy {
    private StackEligibilityPolicy() {
    }

    public static int effectiveLimit(int intrinsicLimit, int playerLimit, boolean preserveVanillaUnstackables) {
        if (intrinsicLimit < 1) {
            throw new IllegalArgumentException("intrinsicLimit must be positive");
        }
        if (playerLimit < 1) {
            throw new IllegalArgumentException("playerLimit must be positive");
        }

        if (preserveVanillaUnstackables && intrinsicLimit == 1) {
            return 1;
        }

        // Never make an item more restrictive than another mod or vanilla already made it.
        // RankStackLimits only raises eligible stack ceilings up to the resolved player limit.
        return Math.max(intrinsicLimit, playerLimit);
    }
}
