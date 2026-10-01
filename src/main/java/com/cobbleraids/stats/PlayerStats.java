package com.cobbleraids.stats;

import com.cobbleraids.config.RaidRarityTier;

/**
 * Everything the leaderboards know about one player: their all-time counters, this week's, and the
 * win streak that runs across both.
 *
 * <p>The weekly block is only valid for the window it was written in. Rather than a scheduled job
 * that resets every player at the turn of the week -- which would be work done on a clock whether or
 * not anyone played -- a stale block is simply ignored when read and replaced when next written. A
 * player who never plays again costs nothing and keeps a week-old block that no board will ever show.
 *
 * <p>The streak is deliberately not weekly: a run of wins carries over Monday morning. What is weekly
 * is the best streak reached within the week, which is what {@link StatBlock#bestWinStreak()} holds.
 *
 * @param name         the last name this player was seen under; a board falls back to the profile
 *                     cache for a player never seen online since the feature existed
 * @param weekWindow   {@link com.cobbleraids.shop.ShopResetPeriod#WEEKLY}'s window number for {@code week}
 */
public record PlayerStats(String name, StatBlock allTime, long weekWindow, StatBlock week, int currentStreak) {

    public static final PlayerStats EMPTY = new PlayerStats("", StatBlock.EMPTY, -1L, StatBlock.EMPTY, 0);

    public PlayerStats {
        name = name == null ? "" : name;
    }

    /** This week's counters, or empty if what is stored belongs to an earlier week. */
    public StatBlock weekIn(long window) {
        return weekWindow == window ? week : StatBlock.EMPTY;
    }

    public StatBlock block(StatScope scope, long window) {
        return scope == StatScope.WEEKLY ? weekIn(window) : allTime;
    }

    public PlayerStats named(String latest) {
        return latest == null || latest.isBlank() || latest.equals(name)
                ? this : new PlayerStats(latest, allTime, weekWindow, week, currentStreak);
    }

    public PlayerStats joined(long window) {
        return new PlayerStats(name, allTime.joined(), window, weekIn(window).joined(), currentStreak);
    }

    public PlayerStats won(long window, RaidRarityTier tier, long damage, int seconds, boolean renowned) {
        int streak = currentStreak + 1;
        return new PlayerStats(name, allTime.won(tier, damage, seconds, renowned, streak), window,
                weekIn(window).won(tier, damage, seconds, renowned, streak), streak);
    }

    public PlayerStats lost(long window, long damage) {
        return new PlayerStats(name, allTime.lost(damage), window, weekIn(window).lost(damage), 0);
    }

    public PlayerStats damageOnly(long window, long damage) {
        if (damage <= 0L) return this;
        return new PlayerStats(name, allTime.damageOnly(damage), window, weekIn(window).damageOnly(damage),
                currentStreak);
    }

    public PlayerStats fled(long window) {
        return new PlayerStats(name, allTime.fled(), window, weekIn(window).fled(), 0);
    }

    public PlayerStats caught(long window) {
        return new PlayerStats(name, allTime.caught(), window, weekIn(window).caught(), currentStreak);
    }

    public PlayerStats earned(long window, int points) {
        if (points <= 0) return this;
        return new PlayerStats(name, allTime.earned(points), window, weekIn(window).earned(points), currentStreak);
    }
}
