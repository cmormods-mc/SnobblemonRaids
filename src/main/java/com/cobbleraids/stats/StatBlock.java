package com.cobbleraids.stats;

import com.cobbleraids.config.RaidRarityTier;

/**
 * One player's counters over one span of time: all of it, or a single week.
 *
 * <p>Immutable, and every event returns a new block, so a block read for a leaderboard can never be
 * half-updated underneath the reader. The same type serves both spans on purpose: "this week" is not
 * a different statistic, it is the same statistic over a shorter window, and one type means a board
 * can rank either without knowing which it was handed.
 *
 * <p>Damage is in raid health-pool units, the same ones the contribution percentages are computed
 * from. A raid's pool scales with party size, level and renown, so it is a measure of how much
 * of a fight a player carried and not of how hard they hit in absolute terms.
 *
 * @param bestWinStreak   the longest run of consecutive wins seen in this span; a loss, or leaving a
 *                        raid, ends a run
 * @param fastestWinSeconds the shortest winning raid, in combat seconds, or 0 when there has been none
 */
public record StatBlock(
        int raidsJoined,
        int raidsWon,
        int raidsLost,
        int raidsFled,
        int winsStarter,
        int winsPowerhouse,
        int winsLegendary,
        int winsMythical,
        long rpEarned,
        long totalDamage,
        long bestRaidDamage,
        int bossesCaught,
        int renownDefeated,
        int bestWinStreak,
        int fastestWinSeconds
) {
    public static final StatBlock EMPTY = new StatBlock(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public StatBlock joined() {
        return new StatBlock(raidsJoined + 1, raidsWon, raidsLost, raidsFled, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned, totalDamage, bestRaidDamage, bossesCaught,
                renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    /**
     * A won raid.
     *
     * @param streakNow the player's win streak INCLUDING this win, so the span can keep its own best
     * @param seconds   combat seconds the raid took; 0 or less is "unknown" and never becomes the record
     */
    public StatBlock won(RaidRarityTier tier, long damage, int seconds, boolean renowned, int streakNow) {
        long dealt = Math.max(0L, damage);
        int fastest = seconds > 0 && (fastestWinSeconds == 0 || seconds < fastestWinSeconds)
                ? seconds : fastestWinSeconds;
        return new StatBlock(raidsJoined, raidsWon + 1, raidsLost, raidsFled,
                winsStarter + (tier == RaidRarityTier.STARTER ? 1 : 0),
                winsPowerhouse + (tier == RaidRarityTier.POWERHOUSE ? 1 : 0),
                winsLegendary + (tier == RaidRarityTier.LEGENDARY ? 1 : 0),
                winsMythical + (tier == RaidRarityTier.MYTHICAL ? 1 : 0),
                rpEarned, totalDamage + dealt, Math.max(bestRaidDamage, dealt), bossesCaught,
                renownDefeated + (renowned ? 1 : 0), Math.max(bestWinStreak, streakNow), fastest);
    }

    /** A raid that was lost while this player was still in it. */
    public StatBlock lost(long damage) {
        long dealt = Math.max(0L, damage);
        return new StatBlock(raidsJoined, raidsWon, raidsLost + 1, raidsFled, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned, totalDamage + dealt, Math.max(bestRaidDamage, dealt),
                bossesCaught, renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    /**
     * Damage dealt in a raid the player is no longer part of at the end -- they withdrew or lost
     * their connection, but what they did to the boss before that still happened.
     */
    public StatBlock damageOnly(long damage) {
        long dealt = Math.max(0L, damage);
        if (dealt == 0L) return this;
        return new StatBlock(raidsJoined, raidsWon, raidsLost, raidsFled, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned, totalDamage + dealt, Math.max(bestRaidDamage, dealt),
                bossesCaught, renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    public StatBlock fled() {
        return new StatBlock(raidsJoined, raidsWon, raidsLost, raidsFled + 1, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned, totalDamage, bestRaidDamage, bossesCaught,
                renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    public StatBlock caught() {
        return new StatBlock(raidsJoined, raidsWon, raidsLost, raidsFled, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned, totalDamage, bestRaidDamage, bossesCaught + 1,
                renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    public StatBlock earned(int points) {
        if (points <= 0) return this;
        return new StatBlock(raidsJoined, raidsWon, raidsLost, raidsFled, winsStarter, winsPowerhouse,
                winsLegendary, winsMythical, rpEarned + points, totalDamage, bestRaidDamage, bossesCaught,
                renownDefeated, bestWinStreak, fastestWinSeconds);
    }

    /** Wins across every tier; what the per-tier counters must add up to. */
    public int tierWins() {
        return winsStarter + winsPowerhouse + winsLegendary + winsMythical;
    }
}
