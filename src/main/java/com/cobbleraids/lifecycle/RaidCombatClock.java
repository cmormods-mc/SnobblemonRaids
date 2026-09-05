package com.cobbleraids.lifecycle;

/** Pure deterministic combat timer. A limit of 0 means unlimited. */
public final class RaidCombatClock {
    private final int limitTicks;
    private int elapsedTicks;

    public RaidCombatClock(int timeLimitSeconds) {
        if (timeLimitSeconds < 0) throw new IllegalArgumentException("timeLimitSeconds must be >= 0");
        long ticks = (long) timeLimitSeconds * 20L;
        if (ticks > Integer.MAX_VALUE) throw new IllegalArgumentException("timeLimitSeconds is too large");
        this.limitTicks = (int) ticks;
    }

    public int getLimitTicks() { return limitTicks; }
    public int getElapsedTicks() { return elapsedTicks; }
    public int getRemainingTicks() { return limitTicks <= 0 ? -1 : Math.max(0, limitTicks - elapsedTicks); }
    public boolean isTimed() { return limitTicks > 0; }

    /** Returns true once: on the tick that reaches the configured limit. */
    public boolean tick() {
        if (limitTicks <= 0 || elapsedTicks >= limitTicks) return false;
        elapsedTicks++;
        return elapsedTicks == limitTicks;
    }
}
