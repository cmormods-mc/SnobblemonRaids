package com.cobbleraids.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeaderboardsTest {

    private static final long WEEK = 3000L;
    private static final Function<UUID, String> NO_NAMES = id -> "";

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static StatsBook book(Object... playerNameWins) {
        StatsBook book = new StatsBook();
        for (int i = 0; i < playerNameWins.length; i += 3) {
            UUID player = id((Integer) playerNameWins[i]);
            String name = (String) playerNameWins[i + 1];
            int wins = (Integer) playerNameWins[i + 2];
            for (int win = 0; win < wins; win++) {
                book.victory(RaidRarityTier.STARTER, Set.of(player), Map.of(player, 10f), 100 + win, false,
                        ignored -> name, WEEK);
            }
        }
        return book;
    }

    @Test
    @DisplayName("players are ranked from most to fewest, and anyone with none is not on the board")
    void ranksDescendingAndOmitsZero() {
        StatsBook book = book(1, "Alice", 3, 2, "Bob", 7, 3, "Cara", 5);
        book.fled(id(9), ignored -> "Dan", WEEK); // a record with no wins at all

        Leaderboards.Board board = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 10, null, NO_NAMES);

        assertEquals(List.of("Bob", "Cara", "Alice"), board.rows().stream().map(Leaderboards.Row::name).toList());
        assertEquals(List.of(1, 2, 3), board.rows().stream().map(Leaderboards.Row::rank).toList());
        assertEquals(List.of(7L, 5L, 3L), board.rows().stream().map(Leaderboards.Row::value).toList());
        assertEquals(3, board.ranked());
    }

    @Test
    @DisplayName("a tie is broken by name, then id, the same way every time, so ranks are never shared")
    void tiesAreStable() {
        StatsBook book = book(1, "bob", 4, 2, "Alice", 4, 3, "Cara", 4);

        for (int read = 0; read < 3; read++) {
            Leaderboards.Board board = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 10, null, NO_NAMES);
            assertEquals(List.of("Alice", "bob", "Cara"), board.rows().stream().map(Leaderboards.Row::name).toList());
            assertEquals(List.of(1, 2, 3), board.rows().stream().map(Leaderboards.Row::rank).toList());
        }
    }

    @Test
    @DisplayName("the fastest-win board ranks the smallest time first, and nobody without a win is on it")
    void fastestWinIsAscending() {
        StatsBook book = new StatsBook();
        book.victory(RaidRarityTier.STARTER, Set.of(id(1)), Map.of(id(1), 5f), 200, false, i -> "Slow", WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(id(2)), Map.of(id(2), 5f), 40, false, i -> "Quick", WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(id(3)), Map.of(id(3), 5f), 0, false, i -> "Unknown", WEEK);
        book.fled(id(4), i -> "Runner", WEEK);

        Leaderboards.Board board = Leaderboards.board(book, LeaderboardStat.FASTEST_WIN, StatScope.ALL_TIME, WEEK, 10, null, NO_NAMES);

        assertEquals(List.of("Quick", "Slow"), board.rows().stream().map(Leaderboards.Row::name).toList());
        assertEquals(LeaderboardStat.Kind.SECONDS, LeaderboardStat.FASTEST_WIN.kind());
        assertTrue(LeaderboardStat.FASTEST_WIN.ascending());
    }

    @Test
    @DisplayName("the limit cuts the list, but the ranked count and the viewer's own row still tell the truth")
    void limitAndViewer() {
        StatsBook book = book(1, "A", 9, 2, "B", 8, 3, "C", 7, 4, "D", 6);

        Leaderboards.Board board = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 2, id(4), NO_NAMES);

        assertEquals(2, board.rows().size());
        assertEquals(4, board.ranked());
        assertEquals(4, board.viewer().rank());
        assertEquals(6, board.viewer().value());
        assertNull(Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 2, id(99), NO_NAMES).viewer());
        assertNull(Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 2, null, NO_NAMES).viewer());
        assertTrue(Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 0, null, NO_NAMES).rows().isEmpty());
    }

    @Test
    @DisplayName("the weekly board only counts this week, and last week's players fall off it")
    void weeklyBoard() {
        StatsBook book = new StatsBook();
        book.victory(RaidRarityTier.STARTER, Set.of(id(1)), Map.of(id(1), 5f), 60, false, i -> "Old", WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(id(1)), Map.of(id(1), 5f), 60, false, i -> "Old", WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(id(2)), Map.of(id(2), 5f), 60, false, i -> "New", WEEK + 1);

        Leaderboards.Board thisWeek = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.WEEKLY, WEEK + 1, 10, null, NO_NAMES);
        Leaderboards.Board allTime = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK + 1, 10, null, NO_NAMES);

        assertEquals(List.of("New"), thisWeek.rows().stream().map(Leaderboards.Row::name).toList());
        assertEquals(List.of("Old", "New"), allTime.rows().stream().map(Leaderboards.Row::name).toList());
    }

    @Test
    @DisplayName("a player with no stored name falls back to the profile cache, then to part of their id")
    void nameFallbacks() {
        StatsBook book = new StatsBook();
        UUID known = id(1);
        UUID unknown = new UUID(0xABCDEF0123456789L, 2);
        book.victory(RaidRarityTier.STARTER, Set.of(known), Map.of(known, 1f), 10, false, i -> "", WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(unknown), Map.of(unknown, 1f), 10, false, i -> "", WEEK);

        Leaderboards.Board board = Leaderboards.board(book, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME, WEEK, 10, null,
                player -> player.equals(known) ? "FromCache" : "");

        assertTrue(board.rows().stream().anyMatch(row -> row.name().equals("FromCache")));
        assertTrue(board.rows().stream().anyMatch(row -> row.name().equals("abcdef01")), board.rows().toString());
        assertTrue(board.rows().stream().noneMatch(row -> row.name().isEmpty()));
    }

    @Test
    @DisplayName("every board has a unique id, parses back from it, and reads a different counter")
    void everyBoardIsReachable() {
        StatBlock block = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        java.util.Set<Long> seen = new java.util.HashSet<>();

        for (LeaderboardStat stat : LeaderboardStat.values()) {
            assertEquals(stat, LeaderboardStat.parse(stat.id()).orElseThrow(), stat.id());
            assertEquals(stat, LeaderboardStat.parse(stat.id().toUpperCase()).orElseThrow(), "case does not matter");
            assertTrue(seen.add(stat.valueOf(block)), stat.id() + " reads the same counter as another board");
        }
        assertEquals(LeaderboardStat.values().length, LeaderboardStat.ids().stream().distinct().count());
        assertTrue(LeaderboardStat.parse("nonsense").isEmpty());
        assertEquals(StatScope.WEEKLY, StatScope.parse("Weekly").orElseThrow());
        assertEquals(StatScope.ALL_TIME, StatScope.parse("alltime").orElseThrow());
        assertTrue(StatScope.parse("monthly").isEmpty());
    }
}
