package com.cobbleraids.network;


/**
 * C2S: turn a page, or change the "mine"/tier filter the Hall of Legends is browsing under. One
 * payload for both, same reasoning as {@link TrophyRoomActionPayload}: opening the screen and every
 * later request carry the exact same fields.
 *
 * <p>Every field here is a request, not an instruction -- {@link com.cobbleraids.catching.
 * LegendGalleryQuery#select} re-validates and clamps all of them.
 */
public record LegendActionPayload(
        int pageIndex,
        int columns,
        boolean mineOnly,
        String tier
) {
    public static final int MAX_TIER_LENGTH = 24;

    /** What {@link com.cobbleraids.catching.LegendRoomGateway#open} sends: page zero, no filters. */
    public static LegendActionPayload initial(int columns) {
        return new LegendActionPayload(0, columns, false, "");
    }
}
