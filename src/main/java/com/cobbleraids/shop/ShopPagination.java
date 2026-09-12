package com.cobbleraids.shop;

/**
 * Which catalogue entries appear on which page.
 *
 * <p>Shared rather than client-side, because both ends need the same answer and they must not
 * disagree: the screen asks for page three, and the server has to resolve a click on its fourth
 * cell to the same entry the player was looking at. A purchase settled by two different opinions
 * of what slot four holds is a player buying something they did not click.
 *
 * <p>Pure arithmetic, so the disagreement can be ruled out by test rather than by hoping.
 */
public record ShopPagination(int totalEntries, int perPage) {

    public ShopPagination {
        if (perPage <= 0) throw new IllegalArgumentException("perPage must be positive: " + perPage);
        if (totalEntries < 0) throw new IllegalArgumentException("totalEntries cannot be negative");
    }

    /** Always at least one, so an empty catalogue still has a page to draw. */
    public int pageCount() {
        return Math.max(1, (totalEntries + perPage - 1) / perPage);
    }

    /** Brings any page number into range, so a stale click cannot address a page that is gone. */
    public int clampPage(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** Wraps past either end, which is what the frame's two arrows imply. */
    public int nextPage(int page) {
        return (clampPage(page) + 1) % pageCount();
    }

    public int previousPage(int page) {
        return (clampPage(page) + pageCount() - 1) % pageCount();
    }

    /** How many cells on this page actually hold something. */
    public int countOn(int page) {
        int first = clampPage(page) * perPage;
        return Math.max(0, Math.min(perPage, totalEntries - first));
    }

    /**
     * The catalogue index shown in {@code slot} on {@code page}, or -1 for an empty cell.
     *
     * <p>The -1 is the point of this method. A trailing cell on the last page holds nothing, and a
     * click on one has to resolve to no entry rather than to whatever happens to sit at that index.
     */
    public int entryIndex(int page, int slot) {
        if (slot < 0 || slot >= perPage) return -1;
        int index = clampPage(page) * perPage + slot;
        return index < totalEntries ? index : -1;
    }
}
