package com.cobbleraids.stats;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * Everything a board can rank by.
 *
 * <p>The id is what a command argument and a future UI both name a board by, so it is the one thing
 * here that must not be renamed once players have typed it.
 */
public enum LeaderboardStat {
    RAIDS_WON("raids_won", "Raids won", Kind.COUNT, false, StatBlock::raidsWon),
    RAIDS_JOINED("raids_joined", "Raids joined", Kind.COUNT, false, StatBlock::raidsJoined),
    RAIDS_LOST("raids_lost", "Raids lost", Kind.COUNT, false, StatBlock::raidsLost),
    RAIDS_FLED("raids_fled", "Raids fled", Kind.COUNT, false, StatBlock::raidsFled),
    WINS_STARTER("wins_starter", "Starter raid wins", Kind.COUNT, false, StatBlock::winsStarter),
    WINS_POWERHOUSE("wins_powerhouse", "Powerhouse raid wins", Kind.COUNT, false, StatBlock::winsPowerhouse),
    WINS_LEGENDARY("wins_legendary", "Legendary raid wins", Kind.COUNT, false, StatBlock::winsLegendary),
    WINS_MYTHICAL("wins_mythical", "Mythical raid wins", Kind.COUNT, false, StatBlock::winsMythical),
    RP_EARNED("rp_earned", "Raid Points earned", Kind.COUNT, false, StatBlock::rpEarned),
    TOTAL_DAMAGE("total_damage", "Total damage dealt", Kind.COUNT, false, StatBlock::totalDamage),
    BEST_RAID_DAMAGE("best_raid_damage", "Most damage in one raid", Kind.COUNT, false, StatBlock::bestRaidDamage),
    BOSSES_CAUGHT("bosses_caught", "Bosses caught", Kind.COUNT, false, StatBlock::bossesCaught),
    RENOWN_DEFEATED("renown_defeated", "Renowned bosses beaten", Kind.COUNT, false, StatBlock::renownDefeated),
    WIN_STREAK("win_streak", "Best win streak", Kind.COUNT, false, StatBlock::bestWinStreak),
    /** The one board where lower is better, and where 0 means "no win yet" rather than "fastest". */
    FASTEST_WIN("fastest_win", "Fastest win", Kind.SECONDS, true, StatBlock::fastestWinSeconds);

    /** How a value reads: a plain number, or a length of time. */
    public enum Kind { COUNT, SECONDS }

    private final String id;
    private final String title;
    private final Kind kind;
    private final boolean ascending;
    private final ToLongFunction<StatBlock> reader;

    LeaderboardStat(String id, String title, Kind kind, boolean ascending, ToLongFunction<StatBlock> reader) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.ascending = ascending;
        this.reader = reader;
    }

    public String id() { return id; }
    public String title() { return title; }
    public Kind kind() { return kind; }
    /** True when the smallest value ranks first. */
    public boolean ascending() { return ascending; }

    /** This player's value on this board. Zero means they do not appear on it. */
    public long valueOf(StatBlock block) {
        return reader.applyAsLong(block);
    }

    public static Optional<LeaderboardStat> parse(String value) {
        if (value == null) return Optional.empty();
        String wanted = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(stat -> stat.id.equals(wanted)).findFirst();
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(LeaderboardStat::id).toList();
    }
}
