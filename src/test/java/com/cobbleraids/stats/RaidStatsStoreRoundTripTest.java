package com.cobbleraids.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one thing a statistics file must never do: come back different. A counter the save forgets
 * is silently zeroed by the first autosave after a restart, which is the kind of failure nobody
 * notices until a player's season has vanished.
 */
class RaidStatsStoreRoundTripTest {

    private static final UUID ALICE = new UUID(1, 1);
    private static final UUID BOB = new UUID(2, 2);

    @AfterEach
    void clear() {
        RaidStatsStore.onServerStopped();
    }

    @Test
    @DisplayName("every counter of every player, the week they belong to and the server totals survive a save")
    void everythingRoundTrips() {
        StatsBook live = RaidStatsStore.book();
        live.start(java.util.List.of(ALICE, BOB), id -> id.equals(ALICE) ? "Alice" : "Bob", 3000L);
        live.victory(RaidRarityTier.MYTHICAL, Set.of(ALICE), Map.of(ALICE, 4321.7f, BOB, 100f), 88, true,
                id -> id.equals(ALICE) ? "Alice" : "Bob", 3000L);
        live.points(ALICE, 125, id -> "Alice", 3000L);
        live.caught(ALICE, id -> "Alice", 3000L);
        live.fled(BOB, id -> "Bob", 3000L);
        live.victory(RaidRarityTier.STARTER, Set.of(ALICE), Map.of(ALICE, 50f), 30, false, id -> "Alice", 3001L);

        CompoundTag tag = new RaidStatsStore().save(new CompoundTag(), null);
        StatsBook restored = RaidStatsStore.load(tag, null).loadedBook();

        assertEquals(live.players().keySet(), restored.players().keySet());
        for (UUID player : live.players().keySet()) {
            assertEquals(live.get(player), restored.get(player), "player " + player);
        }
        assertEquals(live.allTimeTotals(), restored.allTimeTotals());
        assertEquals(live.weekTotals(3001L), restored.weekTotals(3001L));
        assertEquals(live.totalsWindow(), restored.totalsWindow());
        assertEquals(1, restored.get(ALICE).weekIn(3001L).raidsWon(), "the week window came back with the week");
        assertEquals(2, restored.get(ALICE).currentStreak());
    }

    @Test
    @DisplayName("the backfilled flag is saved, so a later start never seeds from old history again")
    void backfillFlagSurvives() {
        RaidStatsStore.book().markBackfilled();

        StatsBook restored = RaidStatsStore.load(new RaidStatsStore().save(new CompoundTag(), null), null).loadedBook();

        assertTrue(restored.backfilled());
    }

    @Test
    @DisplayName("an empty or missing file loads as an empty book, not as an error")
    void emptyLoads() {
        StatsBook restored = RaidStatsStore.load(new CompoundTag(), null).loadedBook();

        assertTrue(restored.players().isEmpty());
        assertEquals(RaidTotals.EMPTY, restored.allTimeTotals());
        assertTrue(!restored.backfilled());
    }

    @Test
    @DisplayName("a block round-trips field for field, including the large damage counters")
    void blockRoundTrips() {
        StatBlock block = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9_000_000_000L, 8_000_000_000L, 7_000_000_000L, 12, 13, 14, 15);

        assertEquals(block, RaidStatsStore.readBlock(RaidStatsStore.writeBlock(block)));
        assertEquals(RaidTotals.EMPTY, RaidStatsStore.readTotals(RaidStatsStore.writeTotals(RaidTotals.EMPTY)));
    }
}
