package com.cobbleraids.client.shop;

/**
 * The Hall of Legends' own layout: a centered panel with a header row, a chronological list of rows,
 * and a footer row -- drawn entirely with flat fills rather than a baked texture (see
 * {@link LegendRoomScreen}'s own header comment for why this screen, unlike {@link TrophyRoomScreen},
 * has no {@code gallery.png}-style art of its own). Same "pure record computes positions, the screen
 * only renders against them" split every other layout in this codebase uses.
 */
public record LegendGalleryLayout(int x, int y, int width, int height) {
    private static final int MAX_WIDTH = 520;
    private static final int MAX_HEIGHT = 360;
    private static final int SCREEN_MARGIN = 24;
    private static final int MIN_USABLE_WIDTH = 260;
    private static final int MIN_USABLE_HEIGHT = 140;

    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 30;
    private static final int ROW_HEIGHT = 34;
    private static final int ROW_GAP = 3;

    public static LegendGalleryLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(0, Math.min(MAX_WIDTH, screenWidth - SCREEN_MARGIN));
        int height = Math.max(0, Math.min(MAX_HEIGHT, screenHeight - SCREEN_MARGIN));
        return new LegendGalleryLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    public boolean usable() {
        return width >= MIN_USABLE_WIDTH && height >= MIN_USABLE_HEIGHT;
    }

    public int headerY() { return y; }
    public int headerHeight() { return HEADER_HEIGHT; }

    public int listY() { return y + HEADER_HEIGHT + 4; }
    public int listHeight() { return height - HEADER_HEIGHT - FOOTER_HEIGHT - 8; }

    /** How many rows fit in the list area -- the page size this layout asks the server for. */
    public int rows() {
        return Math.max(1, (listHeight() + ROW_GAP) / (ROW_HEIGHT + ROW_GAP));
    }

    public int rowY(int index) {
        return listY() + index * (ROW_HEIGHT + ROW_GAP);
    }

    public int rowHeight() {
        return ROW_HEIGHT;
    }

    public int footerY() { return y + height - FOOTER_HEIGHT; }
    public int footerHeight() { return FOOTER_HEIGHT; }
}
