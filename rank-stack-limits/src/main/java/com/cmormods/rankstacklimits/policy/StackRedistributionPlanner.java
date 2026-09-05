package com.cmormods.rankstacklimits.policy;

import java.util.ArrayList;
import java.util.List;

public final class StackRedistributionPlanner {
    private StackRedistributionPlanner() {
    }

    public static List<Integer> splitCount(int count, int newLimit) {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        if (newLimit < 1) {
            throw new IllegalArgumentException("newLimit must be positive");
        }
        if (count == 0) {
            return List.of();
        }

        List<Integer> chunks = new ArrayList<>((count + newLimit - 1) / newLimit);
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, newLimit);
            chunks.add(chunk);
            remaining -= chunk;
        }
        return List.copyOf(chunks);
    }
}
