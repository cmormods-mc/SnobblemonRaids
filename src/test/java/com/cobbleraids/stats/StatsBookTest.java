package com.cobbleraids.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatsBookTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CARA = new UUID(0, 3);
    private static final Function<UUID, String> NAMES = id ->
            id.equals(ALICE) ? "Alice" : id.equals(BOB) ? "Bob" : "Cara";
    private static final long WEEK = 3000L;

    private static StatBlock win(StatsBook book, UUID who, long window) {
        book.victory(RaidRarityTier.STARTER, Set.of(who), Map.of(who, 100f), 60, false, NAMES, window);
        return book.get(who).allTime();
    }

    @Test
    @DisplayName("a win counts once for the player, once for its tier, and adds its damage")
    void aWinIsCounted() {
        StatsBook book = new StatsBook();
        book.start(List.of(ALICE), NAMES, WEEK);
        book.victory(RaidRarityTier.LEGENDARY, Set.of(ALICE), Map.of(ALICE, 1234.4f), 95, true, NAMES, WEEK);

        StatBlock all = book.get(ALICE).allTime();
        assertEquals(1, all.raidsJoined());
        assertEquals(1, all.raidsWon());
        assertEquals(1, all.winsLegendary());
        assertEquals(0, all.winsStarter());
        assertEquals(1234, all.totalDamage());
        assertEquals(1234, all.bestRaidDamage());
        assertEquals(1, all.renownDefeated());
        assertEquals(95, all.fastestWinSeconds());
        assertEquals("Alice", book.get(ALICE).name());
        assertEquals(all, book.get(ALICE).weekIn(WEEK), "the same event lands in this week too");
    }

    @Test
    @DisplayName("the per-tier counters always add up to the wins")
    void tiersAddUp() {
        StatsBook book = new StatsBook();
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            book.victory(tier, Set.of(ALICE), Map.of(ALICE, 10f), 30, false, NAMES, WEEK);
        }
        StatBlock all = book.get(ALICE).allTime();

        assertEquals(RaidRarityTier.values().length, all.raidsWon());
        assertEquals(all.raidsWon(), all.tierWins());
    }

    @Test
    @DisplayName("a win streak builds, a loss ends it, and the best is kept")
    void streaks() {
        StatsBook book = new StatsBook();
        win(book, ALICE, WEEK);
        win(book, ALICE, WEEK);
        win(book, ALICE, WEEK);
        assertEquals(3, book.get(ALICE).currentStreak());

        book.loss(Set.of(ALICE), Map.of(ALICE, 50f), NAMES, WEEK);
        assertEquals(0, book.get(ALICE).currentStreak());
        assertEquals(3, book.get(ALICE).allTime().bestWinStreak(), "the best survives the loss");

        win(book, ALICE, WEEK);
        assertEquals(1, book.get(ALICE).currentStreak());
        assertEquals(3, book.get(ALICE).allTime().bestWinStreak());
    }

    @Test
    @DisplayName("leaving a raid ends a streak and counts as fled, not as a loss")
    void fleeing() {
        StatsBook book = new StatsBook();
        win(book, ALICE, WEEK);
        win(book, ALICE, WEEK);
        book.fled(ALICE, NAMES, WEEK);

        assertEquals(0, book.get(ALICE).currentStreak());
        assertEquals(1, book.get(ALICE).allTime().raidsFled());
        assertEquals(0, book.get(ALICE).allTime().raidsLost());
        assertEquals(2, book.get(ALICE).allTime().bestWinStreak());
    }

    @Test
    @DisplayName("a player who left still has their damage counted, but is not given the win")
    void aLeaverKeepsTheirDamageNotTheWin() {
        StatsBook book = new StatsBook();
        book.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 700f, BOB, 300f), 80, false, NAMES, WEEK);

        assertEquals(1, book.get(ALICE).allTime().raidsWon());
        assertEquals(0, book.get(BOB).allTime().raidsWon());
        assertEquals(0, book.get(BOB).allTime().winsStarter());
        assertEquals(300, book.get(BOB).allTime().totalDamage());
        assertEquals(300, book.get(BOB).allTime().bestRaidDamage());
        assertEquals(0, book.get(BOB).currentStreak());
        assertEquals(1, book.allTimeTotals().won(), "the server counts the raid once");
        assertEquals(1000, book.allTimeTotals().totalDamage());
    }

    @Test
    @DisplayName("a loss counts for whoever was still in, and credits everyone's damage")
    void aLoss() {
        StatsBook book = new StatsBook();
        book.loss(Set.of(ALICE), Map.of(ALICE, 400f, BOB, 100f), NAMES, WEEK);

        assertEquals(1, book.get(ALICE).allTime().raidsLost());
        assertEquals(0, book.get(BOB).allTime().raidsLost(), "they had already left");
        assertEquals(100, book.get(BOB).allTime().totalDamage());
        assertEquals(1, book.allTimeTotals().lost());
    }

    @Test
    @DisplayName("a raid everyone had left keeps its damage but is not a defeat anyone suffered")
    void anAbandonedRaidIsNotALoss() {
        StatsBook book = new StatsBook();
        book.start(List.of(ALICE), NAMES, WEEK);
        book.loss(Set.of(), Map.of(ALICE, 250f), NAMES, WEEK);

        assertEquals(0, book.get(ALICE).allTime().raidsLost());
        assertEquals(250, book.get(ALICE).allTime().totalDamage());
        assertEquals(0, book.allTimeTotals().lost());
        assertEquals(250, book.allTimeTotals().totalDamage());
        assertEquals(1, book.allTimeTotals().started());
    }

    @Test
    @DisplayName("the fastest win is the smallest time, an unknown time never counts, and the best raid is the largest")
    void recordsKeepTheBest() {
        StatsBook book = new StatsBook();
        book.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 500f), 120, false, NAMES, WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 900f), 45, false, NAMES, WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 200f), 300, false, NAMES, WEEK);
        book.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 100f), 0, false, NAMES, WEEK);

        assertEquals(45, book.get(ALICE).allTime().fastestWinSeconds());
        assertEquals(900, book.get(ALICE).allTime().bestRaidDamage());
        assertEquals(1700, book.get(ALICE).allTime().totalDamage());
    }

    @Test
    @DisplayName("Raid Points earned adds up, and a zero or negative amount is not a statistic")
    void pointsEarned() {
        StatsBook book = new StatsBook();
        book.points(ALICE, 31, NAMES, WEEK);
        book.points(ALICE, 25, NAMES, WEEK);
        book.points(ALICE, 0, NAMES, WEEK);
        book.points(ALICE, -50, NAMES, WEEK);

        assertEquals(56, book.get(ALICE).allTime().rpEarned());
        assertEquals(56, book.allTimeTotals().rpPaid());
    }

    @Test
    @DisplayName("a new week starts the weekly counters from zero and leaves all-time alone")
    void weeklyRollover() {
        StatsBook book = new StatsBook();
        win(book, ALICE, WEEK);
        win(book, ALICE, WEEK);
        win(book, ALICE, WEEK + 1);

        PlayerStats stats = book.get(ALICE);
        assertEquals(3, stats.allTime().raidsWon());
        assertEquals(1, stats.weekIn(WEEK + 1).raidsWon(), "only this week's win");
        assertEquals(StatBlock.EMPTY, stats.weekIn(WEEK), "last week is no longer readable");
        assertEquals(3, stats.currentStreak(), "a streak runs through Monday morning");
        assertEquals(3, stats.weekIn(WEEK + 1).bestWinStreak(), "and the week's best is the streak it reached");
        assertEquals(1, book.weekTotals(WEEK + 1).won());
        assertEquals(3, book.allTimeTotals().won());
    }

    @Test
    @DisplayName("a player who does not play in a new week still reads as empty for it")
    void staleWeekReadsEmpty() {
        StatsBook book = new StatsBook();
        win(book, ALICE, WEEK);

        assertEquals(StatBlock.EMPTY, book.get(ALICE).weekIn(WEEK + 5));
        assertEquals(1, book.get(ALICE).block(StatScope.ALL_TIME, WEEK + 5).raidsWon());
        assertEquals(RaidTotals.EMPTY, book.weekTotals(WEEK + 5));
    }

    @Test
    @DisplayName("a blank name never overwrites a known one")
    void namesAreKept() {
        PlayerStats stats = PlayerStats.EMPTY.named("Alice");
        assertEquals("Alice", stats.named("").name());
        assertEquals("Alice", stats.named(null).name());
        assertEquals("Alicia", stats.named("Alicia").name());
        assertSame(stats, stats.named("Alice"));
    }

    @Test
    @DisplayName("history from before the leaderboards seeds only what the old records could know")
    void seedFromHistory() {
        PlayerStats seeded = StatsBook.seedFromHistory("Cara", 10, 6, 3, 1, 0, 2, 4);

        assertEquals(10, seeded.allTime().raidsWon());
        assertEquals(10, seeded.allTime().raidsJoined());
        assertEquals(10, seeded.allTime().tierWins());
        assertEquals(2, seeded.allTime().bossesCaught());
        assertEquals(4, seeded.allTime().renownDefeated());
        assertEquals(0, seeded.allTime().totalDamage(), "nothing invented");
        assertEquals(0, seeded.allTime().rpEarned());
        assertEquals(0, seeded.allTime().fastestWinSeconds());
        assertEquals(StatBlock.EMPTY, seeded.weekIn(WEEK));
    }

    @Test
    @DisplayName("backfill never overwrites a player who already has statistics")
    void backfillOnlyFillsEmpty() {
        StatsBook book = new StatsBook();
        win(book, ALICE, WEEK);
        book.backfill(ALICE, StatsBook.seedFromHistory("Alice", 50, 50, 0, 0, 0, 0, 0));
        book.backfill(CARA, StatsBook.seedFromHistory("Cara", 5, 5, 0, 0, 0, 0, 0));

        assertEquals(1, book.get(ALICE).allTime().raidsWon());
        assertEquals(5, book.get(CARA).allTime().raidsWon());
        assertTrue(!book.backfilled());
        book.markBackfilled();
        assertTrue(book.backfilled());
    }
}
