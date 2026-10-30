package com.cobbleraids.charges;

/** Immutable wall-clock token bucket. Offline time refills it; time at full cannot be banked. */
public record RaidChargeAccount(int available, long nextRefillAt) {
    public static final int CAPACITY = 3;
    public static final long REFILL_MILLIS = 3_600_000L;
    public static final RaidChargeAccount FULL = new RaidChargeAccount(CAPACITY, 0);
    public RaidChargeAccount {
        if (available < 0 || available > CAPACITY || (available < CAPACITY && nextRefillAt <= 0)
                || (available == CAPACITY && nextRefillAt != 0)) throw new IllegalArgumentException("Invalid raid charge account");
    }
    public RaidChargeAccount at(long now) {
        if (available == CAPACITY || now < nextRefillAt) return this;
        long restored = 1 + (now - nextRefillAt) / REFILL_MILLIS;
        if (restored >= CAPACITY - available) return FULL;
        return new RaidChargeAccount(available + (int) restored, nextRefillAt + restored * REFILL_MILLIS);
    }
    public RaidChargeAccount spend(long now) {
        RaidChargeAccount current = at(now);
        if (current.available == 0) throw new IllegalStateException("No raid charges remaining");
        return new RaidChargeAccount(current.available - 1,
                current.available == CAPACITY ? now + REFILL_MILLIS : current.nextRefillAt);
    }
    public long secondsToNext(long now) {
        RaidChargeAccount current = at(now);
        return current.available == CAPACITY ? 0 : Math.max(0, (current.nextRefillAt - now + 999) / 1000);
    }
    public long secondsToFull(long now) {
        RaidChargeAccount current = at(now);
        return current.available == CAPACITY ? 0 : current.secondsToNext(now) + (CAPACITY - current.available - 1) * 3600L;
    }
}
