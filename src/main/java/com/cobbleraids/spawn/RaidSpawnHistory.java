package com.cobbleraids.spawn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Bounded, in-memory record of recent natural-spawn attempts and why each succeeded or was
 * rejected. attemptForPlayer previously just returned on each rejection with nothing to inspect
 * afterward; this is what /cobbleraids debug history reads from.
 */
public final class RaidSpawnHistory {
    public enum Outcome {
        SUCCESS,
        PER_DIMENSION_CAP,
        NO_VALID_TERRAIN,
        TOO_CLOSE_TO_EXISTING,
        NO_ELIGIBLE_DEFINITIONS,
        TIER_SELECTION_FAILED
    }

    public record Entry(long tick, String player, ResourceLocation dimension, Outcome outcome, String detail) {}

    private static final int CAPACITY = 50;
    private static final Deque<Entry> RECENT = new ArrayDeque<>();

    private RaidSpawnHistory() {}

    public static synchronized void record(long tick, String player, ResourceLocation dimension, Outcome outcome, String detail) {
        RECENT.addLast(new Entry(tick, player, dimension, outcome, detail));
        while (RECENT.size() > CAPACITY) RECENT.removeFirst();
    }

    /** Oldest first. */
    public static synchronized List<Entry> recent() {
        return List.copyOf(RECENT);
    }
}
