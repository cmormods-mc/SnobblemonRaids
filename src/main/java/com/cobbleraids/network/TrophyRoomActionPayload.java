package com.cobbleraids.network;


/**
 * C2S: turn a page, or change the search/filter/sort the trophy room is browsing under. One payload
 * for both, the same reason {@link ShopActionPayload} is: opening the screen and every later request
 * carry the exact same fields, since a page turn is just a request that repeats the current filters.
 *
 * <p>Every field here is a request, not an instruction -- {@link com.cobbleraids.catching.
 * TrophyGalleryQuery#select} re-validates and clamps all of them, so a hostile or stale client can
 * only ever ask for something nonsensical, never receive it.
 *
 * <p>{@code shiny}/{@code sort} travel as raw ordinals rather than strings: both enums are this
 * mod's own and never leave the JVM's memory except over this one channel, so there is no format to
 * keep stable across a save file or a config an operator might hand-edit.
 */
public record TrophyRoomActionPayload(
        int pageIndex,
        int columns,
        String query,
        String tier,
        int shiny,
        int sort
) {
    /** Longer than any search term the screen's own search box will let a player type. */
    public static final int MAX_QUERY_LENGTH = 64;
    public static final int MAX_TIER_LENGTH = 24;

    /** What {@link com.cobbleraids.catching.TrophyRoomGateway#open} sends: page zero, no filters. */
    public static TrophyRoomActionPayload initial(int columns) {
        return new TrophyRoomActionPayload(0, columns, "", "", 0, 0);
    }
}
