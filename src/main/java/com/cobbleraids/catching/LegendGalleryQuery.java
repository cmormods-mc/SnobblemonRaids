package com.cobbleraids.catching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Filter and paginate the Hall of Legends. Pure and Minecraft-free, same reasoning as
 * {@link TrophyGalleryQuery} -- the whole thing is testable without a server.
 *
 * <p>Deliberately simpler than {@code TrophyGalleryQuery}: no search box and no {@code Sort} enum.
 * A legend has no analogue to "best IV" or a species catalog's alphabetical browse -- it is a
 * chronological record of firsts, so newest-first is the only ordering that makes sense and is not
 * offered as a choice.
 */
public final class LegendGalleryQuery {
    private LegendGalleryQuery() {}

    /**
     * One page of results, plus the counts the screen's header needs.
     *
     * @param total     how many legends pass the tier filter, regardless of {@code mineOnly} -- the
     *                  screen reads this while browsing everyone's.
     * @param mineCount how many of that same tier-filtered set credit the requesting player,
     *                  regardless of {@code mineOnly} -- the screen reads this while {@code mineOnly}
     *                  is on, or to show "3 of yours" as a hint while still browsing everyone's.
     */
    public record Page(int index, int count, int total, int mineCount, List<LegendEntry> entries) {
    }

    /** The largest number of rows {@link #select} will ever page by. */
    public static final int MAX_COLUMNS = 8;

    /**
     * Runs the tier/"mine" filter over every legend on the server and slices out one page, newest
     * first.
     *
     * @param columns  how many rows the screen has room for. Clamped, since it travels over the
     *                 network from a client whose window the server has no reason to trust.
     * @param viewer   the requesting player, always known server-side regardless of {@code mineOnly}
     *                 -- this is what lets {@link Page#mineCount()} say "3 of yours" while
     *                 {@code mineOnly} is off and the screen is browsing everyone's legends. {@code null}
     *                 only in a context with no real player (e.g. a future console/API caller).
     * @param mineOnly narrows the page itself to entries crediting {@code viewer}; does not change
     *                 what {@link Page#mineCount()} counts.
     * @param tierName a {@code RaidRarityTier} serialized name, or empty for every tier.
     */
    public static Page select(Iterable<LegendEntry> all, int requestedIndex, int columns,
                              UUID viewer, boolean mineOnly, String tierName) {
        columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
        String tier = tierName == null ? "" : tierName.toLowerCase(Locale.ROOT);

        int total = 0;
        int mineCount = 0;
        List<LegendEntry> matched = new ArrayList<>();
        for (LegendEntry entry : all) {
            if (!tier.isEmpty() && !entry.tier().serializedName().equals(tier)) continue;
            total++;
            boolean mine = viewer != null && entry.victorIds().contains(viewer);
            if (mine) mineCount++;
            if (mineOnly && !mine) continue;
            matched.add(entry);
        }
        matched.sort(Comparator.comparingLong(LegendEntry::defeatedAtEpochMs).reversed());

        int pageCount = Math.max(1, (matched.size() + columns - 1) / columns);
        int index = Math.max(0, Math.min(requestedIndex, pageCount - 1));
        int from = Math.min(index * columns, matched.size());
        int to = Math.min(from + columns, matched.size());

        return new Page(index, pageCount, total, mineCount, List.copyOf(matched.subList(from, to)));
    }
}
