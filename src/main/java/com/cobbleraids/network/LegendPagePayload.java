package com.cobbleraids.network;

import java.util.List;

/**
 * S2C: one page of the server's Hall of Legends, under whatever "mine"/tier filter it was requested
 * with. Sent fresh on every open, page turn and filter change, same reasoning as
 * {@link TrophyRoomPagePayload}: a legend recorded mid-session should show up on the next turn back
 * to that page, not stay stale.
 *
 * <p>{@code total}/{@code mineCount} are counts after the tier filter but before "mine", so the
 * header can say "3 of yours, 40 total" while browsing everyone's.
 */
public record LegendPagePayload(
        int pageIndex,
        int pageCount,
        int total,
        int mineCount,
        List<LegendEntryPayload> entries
) {
    private static final int MAX_ENTRIES = com.cobbleraids.catching.LegendGalleryQuery.MAX_COLUMNS;
}
