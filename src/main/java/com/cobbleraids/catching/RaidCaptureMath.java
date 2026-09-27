package com.cobbleraids.catching;

/** Pure capture-minigame math kept independent of Minecraft for deterministic unit validation. */
public final class RaidCaptureMath {
    private RaidCaptureMath() {}

    /**
     * Scores one timing input against a zone centered on the perfect instant.
     *
     * @param offsetFromZoneCenterMs how far off-center the input landed, in either direction
     * @param goodZoneWidthMs        the full width of the outer (partial-credit) zone
     * @param perfectZoneWidthMs     the full width of the inner (full-credit) zone, must fit inside the good zone
     * @return 1.0 inside the perfect zone, 0.5 inside the good zone but outside the perfect one, else 0.0
     */
    public static double judge(long offsetFromZoneCenterMs, int goodZoneWidthMs, int perfectZoneWidthMs) {
        long distance = Math.abs(offsetFromZoneCenterMs);
        if (distance <= perfectZoneWidthMs / 2.0) return 1.0;
        if (distance <= goodZoneWidthMs / 2.0) return 0.5;
        return 0.0;
    }

    /**
     * How far {@code nowEpochMs} falls from the nearest instant the indicator crosses the track's
     * fixed center, given the indicator keeps sweeping back and forth every {@code travelDurationMs}
     * for as long as the player takes to press. Without this, a press on the second, third or later
     * pass would score against an ever-growing absolute elapsed time and land nowhere near either
     * zone -- this instead measures against whichever crossing the press actually landed nearest,
     * which is what lets the client bounce the indicator indefinitely rather than stopping dead the
     * instant a single one-shot sweep finishes.
     */
    public static long offsetFromNearestCenter(long nowEpochMs, long stepStartedAtEpochMs, int travelDurationMs) {
        long elapsed = nowEpochMs - stepStartedAtEpochMs;
        long positionInCycle = Math.floorMod(elapsed, (long) travelDurationMs);
        return positionInCycle - travelDurationMs / 2L;
    }

    /** The mean of the three stabilization pulses, each already scored by {@link #judge}. */
    public static double stabilizationQuality(double pulse1Score, double pulse2Score, double pulse3Score) {
        return (pulse1Score + pulse2Score + pulse3Score) / 3.0;
    }

    /**
     * The final capture chance for one attempt: the tier's floor plus each half of the minigame's
     * bonus, scaled by how well it was played. Quality inputs are expected in {@code 0..1}, which
     * keeps the result within {@code tierBase..(tierBase + stabilizationCap + throwCap)} on its own;
     * the clamp below is a safety net against a caller passing an out-of-range quality, not the
     * mechanism that enforces the floor -- bonuses are additive-only by construction, never negative.
     */
    public static double finalChance(double tierBase, double stabilizationCap, double throwCap,
                                      double stabilizationQuality, double throwQuality) {
        double ceiling = Math.min(1.0, tierBase + stabilizationCap + throwCap);
        double chance = tierBase + stabilizationQuality * stabilizationCap + throwQuality * throwCap;
        return Math.min(ceiling, Math.max(tierBase, chance));
    }
}
