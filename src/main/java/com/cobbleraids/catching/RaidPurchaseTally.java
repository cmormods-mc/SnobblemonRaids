package com.cobbleraids.catching;

/**
 * How many times a player has bought one shop entry, and which day that count belongs to.
 *
 * <p>The day is stored with the count rather than the counts being wiped at midnight. Nothing has
 * to run on a schedule, a server that was offline all day does not need to catch up, and a player
 * who has not logged in for a month is not carrying a stale count -- their tally simply names a day
 * that is not today, and reads as zero.
 *
 * <p>It also means an entry can change its reset period without its history becoming a lie: the
 * count and the day it was set are facts, and what they mean is decided when they are read.
 */
public record RaidPurchaseTally(int count, long day) {

    public static final RaidPurchaseTally NONE = new RaidPurchaseTally(0, 0L);

    public RaidPurchaseTally {
        if (count < 0) throw new IllegalArgumentException("purchase count cannot be negative");
    }

    /**
     * The tally after one more purchase on {@code today}.
     *
     * <p>A purchase on a different day starts the count again rather than adding to it, which is
     * what makes a daily limit daily.
     */
    public RaidPurchaseTally increment(long today) {
        return day == today
                ? new RaidPurchaseTally(count + 1, today)
                : new RaidPurchaseTally(1, today);
    }

    /** What the count is worth now: the stored count, or zero once its day has passed. */
    public int countOn(long today, boolean resets) {
        if (!resets) return count;
        return day == today ? count : 0;
    }
}
