package com.cobbleraids.renown;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.random.RandomGenerator;

/**
 * The arithmetic of renown: whether a boss rolls it, what its stat focus may add, and what beating
 * it pays. Kept apart from the places that apply it so each rule is testable without a server.
 */
public final class RenownRewards {

    /** Cobblemon's EV limits (EVs.MAX_STAT_VALUE / MAX_TOTAL_VALUE), restated to stay Minecraft-free. */
    public static final int MAX_STAT_EVS = 252;
    public static final int MAX_TOTAL_EVS = 510;

    private RenownRewards() {}

    public static boolean rolls(double chance, RandomGenerator random) {
        return chance > 0.0 && random.nextDouble() < chance;
    }

    /**
     * The EVs a focused stat ends up with.
     *
     * <p>Clamped against both limits here rather than trusting Cobblemon's setter: a definition may
     * already pin EVs through its traits block, and an unclamped total over 510 is a Pokemon no
     * legitimate path could produce. Never lowers a stat the definition already raised.
     */
    public static int focusEvs(int current, int total, int bonus) {
        int safeCurrent = Math.max(0, Math.min(MAX_STAT_EVS, current));
        int headroom = Math.max(0, MAX_TOTAL_EVS - Math.max(0, total));
        int added = Math.max(0, Math.min(bonus, headroom));
        return Math.min(MAX_STAT_EVS, safeCurrent + added);
    }

    /** Floors, like RaidCurrencyPolicy, so a multiplier can never pay more than it says. */
    public static int points(int base, double multiplier) {
        if (base <= 0) return 0;
        double scaled = Math.floor(base * Math.max(1.0, multiplier));
        return scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    public static BigInteger currency(BigInteger base, double multiplier) {
        if (base == null || base.signum() <= 0) return BigInteger.ZERO;
        return new BigDecimal(base).multiply(BigDecimal.valueOf(Math.max(1.0, multiplier)))
                .setScale(0, RoundingMode.FLOOR).toBigInteger();
    }
}
