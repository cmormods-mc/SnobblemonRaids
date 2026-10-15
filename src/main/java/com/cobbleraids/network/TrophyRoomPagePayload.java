package com.cobbleraids.network;

import java.util.List;

/**
 * S2C: one page of a player's trophy room, under whatever search/filter/sort it was requested with.
 *
 * <p>Sent fresh on every open, page turn and filter change rather than the client caching pages
 * itself, the same choice {@link ShopPagePayload} makes -- a trophy earned mid-session should show up
 * on the next turn back to that page, not stay stale.
 *
 * <p>{@code total}/{@code shinyTotal} are unfiltered counts across the player's whole trophy room, so
 * the header can say "4 of 130 trophies" even while a search narrows what {@code entries} holds.
 * {@code filteredCount} is how many trophies matched the current search/filter, which is what
 * pagination is computed against.
 */
public record TrophyRoomPagePayload(
        int pageIndex,
        int pageCount,
        int total,
        int shinyTotal,
        int filteredCount,
        List<TrophyRoomEntryPayload> entries
) {
    /** {@link com.cobbleraids.catching.TrophyGalleryQuery#MAX_COLUMNS} times two, for the preview strip. */
    private static final int MAX_ENTRIES = com.cobbleraids.catching.TrophyGalleryQuery.MAX_COLUMNS * 2;
}
