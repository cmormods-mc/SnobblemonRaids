package com.cobbleraids.stats;

/**
 * Server-wide counters, counting RAIDS rather than players: a four-player win is one win here and
 * four in the players' own records. Kept as its own totals instead of summing the players, because
 * a sum could not tell one four-player raid from four solo ones.
 *
 * @param rpPaid Raid Points granted for raids and captures, all players together
 */
public record RaidTotals(int started, int won, int lost, int caught, long totalDamage, long rpPaid) {

    public static final RaidTotals EMPTY = new RaidTotals(0, 0, 0, 0, 0L, 0L);

    public RaidTotals onStart() {
        return new RaidTotals(started + 1, won, lost, caught, totalDamage, rpPaid);
    }

    public RaidTotals onWin(long damage) {
        return new RaidTotals(started, won + 1, lost, caught, totalDamage + Math.max(0L, damage), rpPaid);
    }

    public RaidTotals onLoss(long damage) {
        return new RaidTotals(started, won, lost + 1, caught, totalDamage + Math.max(0L, damage), rpPaid);
    }

    /** Damage dealt in a raid that ended with nobody left in it: it happened, but nobody was beaten. */
    public RaidTotals onDamage(long damage) {
        return damage <= 0L ? this : new RaidTotals(started, won, lost, caught, totalDamage + damage, rpPaid);
    }

    public RaidTotals onCatch() {
        return new RaidTotals(started, won, lost, caught + 1, totalDamage, rpPaid);
    }

    public RaidTotals onPayout(int points) {
        return points <= 0 ? this : new RaidTotals(started, won, lost, caught, totalDamage, rpPaid + points);
    }
}
