package com.cobbleraids.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns a {@link StatsBook} into ranked boards.
 *
 * <p>Pure and computed on demand: nothing here is stored or kept sorted. A board is a read of however
 * many players the server has ever seen, done when somebody asks for it, which is far cheaper than
 * keeping a ranking current through every raid event that nobody is looking at.
 *
 * <p>This is also the surface a leaderboard screen should call. {@link Board} carries everything a
 * screen draws: the rows, how many players are ranked, and which row is the viewer's own.
 */
public final class Leaderboards {

    private Leaderboards() {}

    /**
     * @param rank   1-based. Equal values share no rank: ties are broken by name and then id, so a
     *               board never shows two players at the same position or reshuffles between reads
     * @param name   what to print; never empty
     */
    public record Row(int rank, UUID player, String name, long value) {}

    /**
     * One board.
     *
     * @param ranked   how many players have a value at all, which can exceed {@code rows.size()}
     * @param viewer   the asker's own row wherever they stand, or null when they are not ranked or no
     *                 viewer was named; lets a board say "you are #14" without paging to it
     */
    public record Board(LeaderboardStat stat, StatScope scope, long window, List<Row> rows, int ranked, Row viewer) {}

    /**
     * @param limit  rows to return; values below 1 give none
     * @param viewer whose own row to include, or null
     * @param nameOf last resort for a player whose stored name is empty
     */
    public static Board board(StatsBook book, LeaderboardStat stat, StatScope scope, long window, int limit,
                              UUID viewer, Function<UUID, String> nameOf) {
        List<Sortable> everyone = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : book.players().entrySet()) {
            long value = stat.valueOf(entry.getValue().block(scope, window));
            if (value <= 0L) continue;
            String stored = entry.getValue().name();
            String name = stored.isEmpty() ? nameOf.apply(entry.getKey()) : stored;
            Row row = new Row(0, entry.getKey(), name == null || name.isEmpty() ? shortId(entry.getKey()) : name, value);
            everyone.add(new Sortable(row, row.name().toLowerCase(Locale.ROOT)));
        }
        // The lower-cased name is worked out once per player above. Comparing with a key extractor
        // that lower-cased inside the comparator allocated a string on every comparison, which on a
        // server that has seen thousands of players is tens of thousands of throwaway strings per board.
        Comparator<Sortable> byValue = Comparator.comparingLong(sortable -> sortable.row().value());
        if (!stat.ascending()) byValue = byValue.reversed();
        everyone.sort(byValue
                .thenComparing(Sortable::sortName)
                .thenComparing(sortable -> sortable.row().player()));

        List<Row> ranked = new ArrayList<>(everyone.size());
        Row own = null;
        for (int index = 0; index < everyone.size(); index++) {
            Row row = everyone.get(index).row();
            Row numbered = new Row(index + 1, row.player(), row.name(), row.value());
            ranked.add(numbered);
            if (numbered.player().equals(viewer)) own = numbered;
        }
        List<Row> top = ranked.subList(0, Math.max(0, Math.min(limit, ranked.size())));
        return new Board(stat, scope, window, List.copyOf(top), ranked.size(), own);
    }

    /** A row with the lower-cased name it sorts by, computed once. */
    private record Sortable(Row row, String sortName) {}

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
