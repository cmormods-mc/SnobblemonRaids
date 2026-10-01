package com.cobbleraids.stats;

import com.cobbleraids.config.RaidRarityTier;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every statistic the server keeps, and the rules for how each raid event changes them.
 *
 * <p>Free of Minecraft types so the rules -- streaks, the weekly window, whose damage counts when --
 * are unit-tested whole. {@link RaidStatsStore} is the thin layer that saves one of these to the
 * world and feeds it from the raid lifecycle. Not thread-safe by design: every raid event arrives on
 * the server thread.
 *
 * <p>The window is passed in rather than read from a clock, which is what lets a test cross
 * Monday morning without waiting for it.
 */
public final class StatsBook {

    private final Map<UUID, PlayerStats> players = new LinkedHashMap<>();
    private RaidTotals allTotals = RaidTotals.EMPTY;
    private long totalsWindow = -1L;
    private RaidTotals weekTotals = RaidTotals.EMPTY;
    private boolean backfilled;

    public StatsBook() {}

    // ---------------------------------------------------------------- reading

    public PlayerStats get(UUID player) {
        return players.getOrDefault(player, PlayerStats.EMPTY);
    }

    public Map<UUID, PlayerStats> players() {
        return Collections.unmodifiableMap(players);
    }

    public RaidTotals allTimeTotals() {
        return allTotals;
    }

    public RaidTotals weekTotals(long window) {
        return totalsWindow == window ? weekTotals : RaidTotals.EMPTY;
    }

    public long totalsWindow() {
        return totalsWindow;
    }

    public boolean backfilled() {
        return backfilled;
    }

    // ---------------------------------------------------------------- raid events

    /** A raid's battle began with these players in it. */
    public void start(Collection<UUID> joined, Function<UUID, String> names, long window) {
        for (UUID id : joined) update(id, names, stats -> stats.joined(window));
        totals(window, current -> current.onStart(), all -> all.onStart());
    }

    /**
     * A raid was won.
     *
     * <p>Everyone who dealt damage is credited with it, including a player who had already left: a
     * leaver gets the damage but not the win, the win streak or the tier count, because they did not
     * finish. {@code damage} may name players who are in neither group; they are credited too.
     *
     * @param victors the players still in the raid when it was won
     * @param damage  each player's damage, raid health-pool units
     * @param seconds combat seconds the raid lasted
     */
    public void victory(RaidRarityTier tier, Set<UUID> victors, Map<UUID, Float> damage, int seconds,
                        boolean renowned, Function<UUID, String> names, long window) {
        long raidDamage = 0L;
        for (Map.Entry<UUID, Float> entry : damage.entrySet()) {
            long dealt = Math.round(entry.getValue());
            raidDamage += Math.max(0L, dealt);
            if (!victors.contains(entry.getKey())) {
                update(entry.getKey(), names, stats -> stats.damageOnly(window, dealt));
            }
        }
        for (UUID id : victors) {
            long dealt = Math.round(damage.getOrDefault(id, 0f));
            update(id, names, stats -> stats.won(window, tier, dealt, seconds, renowned));
        }
        long total = raidDamage;
        totals(window, current -> current.onWin(total), all -> all.onWin(total));
    }

    /** A raid was lost. Same crediting of damage as a win; the streak ends for whoever was still in. */
    public void loss(Set<UUID> remaining, Map<UUID, Float> damage, Function<UUID, String> names, long window) {
        long raidDamage = 0L;
        for (Map.Entry<UUID, Float> entry : damage.entrySet()) {
            long dealt = Math.round(entry.getValue());
            raidDamage += Math.max(0L, dealt);
            if (!remaining.contains(entry.getKey())) {
                update(entry.getKey(), names, stats -> stats.damageOnly(window, dealt));
            }
        }
        for (UUID id : remaining) {
            long dealt = Math.round(damage.getOrDefault(id, 0f));
            update(id, names, stats -> stats.lost(window, dealt));
        }
        long total = raidDamage;
        if (remaining.isEmpty()) {
            // Everyone had already left: the raid failed, but no player was beaten in it. Their damage
            // is real and is kept; counting it as a loss would credit the server with a defeat nobody
            // suffered.
            totals(window, current -> current.onDamage(total), all -> all.onDamage(total));
            return;
        }
        totals(window, current -> current.onLoss(total), all -> all.onLoss(total));
    }

    /** A player withdrew, or their disconnect grace ran out. */
    public void fled(UUID player, Function<UUID, String> names, long window) {
        update(player, names, stats -> stats.fled(window));
    }

    public void caught(UUID player, Function<UUID, String> names, long window) {
        update(player, names, stats -> stats.caught(window));
        totals(window, current -> current.onCatch(), all -> all.onCatch());
    }

    /** Raid Points granted for a win or a capture. Never a shop refund, an admin grant or a spend. */
    public void points(UUID player, int amount, Function<UUID, String> names, long window) {
        if (amount <= 0) return;
        update(player, names, stats -> stats.earned(window, amount));
        totals(window, current -> current.onPayout(amount), all -> all.onPayout(amount));
    }

    // ---------------------------------------------------------------- loading

    /** Puts a player's stats back exactly as they were saved. */
    public void restore(UUID player, PlayerStats stats) {
        players.put(player, stats);
    }

    public void restoreTotals(RaidTotals all, long window, RaidTotals week, boolean backfilledAlready) {
        allTotals = all;
        totalsWindow = window;
        weekTotals = week;
        backfilled = backfilledAlready;
    }

    /**
     * Seeds a player from history recorded before the leaderboards existed. Only fills a player with
     * nothing yet, and marks the book so it is never done twice.
     */
    public void backfill(UUID player, PlayerStats seeded) {
        players.putIfAbsent(player, seeded);
    }

    public void markBackfilled() {
        backfilled = true;
    }

    public void clear() {
        players.clear();
        allTotals = RaidTotals.EMPTY;
        totalsWindow = -1L;
        weekTotals = RaidTotals.EMPTY;
        backfilled = false;
    }

    /** Replaces this book's contents with another's, which is how a freshly loaded book goes live. */
    public void copyFrom(StatsBook other) {
        clear();
        players.putAll(other.players);
        allTotals = other.allTotals;
        totalsWindow = other.totalsWindow;
        weekTotals = other.weekTotals;
        backfilled = other.backfilled;
    }

    /**
     * What a player's all-time block is worth when all that exists is the raid history recorded before
     * the leaderboards. Joined is set to the wins, the least it could be; nothing else is invented.
     */
    public static PlayerStats seedFromHistory(String name, int wins, int starter, int powerhouse, int legendary,
                                              int mythical, int caught, int renownDefeated) {
        StatBlock block = new StatBlock(wins, wins, 0, 0, starter, powerhouse, legendary, mythical,
                0L, 0L, 0L, caught, renownDefeated, 0, 0);
        return new PlayerStats(name, block, -1L, StatBlock.EMPTY, 0);
    }

    // ---------------------------------------------------------------- internals

    private void update(UUID id, Function<UUID, String> names,
                        java.util.function.UnaryOperator<PlayerStats> change) {
        PlayerStats current = players.getOrDefault(id, PlayerStats.EMPTY);
        players.put(id, change.apply(current.named(names.apply(id))));
    }

    private void totals(long window, java.util.function.UnaryOperator<RaidTotals> week,
                        java.util.function.UnaryOperator<RaidTotals> all) {
        if (totalsWindow != window) {
            totalsWindow = window;
            weekTotals = RaidTotals.EMPTY;
        }
        weekTotals = week.apply(weekTotals);
        allTotals = all.apply(allTotals);
    }
}
