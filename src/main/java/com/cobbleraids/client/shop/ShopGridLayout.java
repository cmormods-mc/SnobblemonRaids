package com.cobbleraids.client.shop;

/**
 * Where the shop's cells sit inside the frame's panel.
 *
 * <p>The frame art carries no grid: the boxes drawn into the supplied image were flattened out, so
 * every cell here is drawn by the screen. That is what lets a cell highlight under the cursor, dim
 * when it costs more than the player has, and be laid out at a count the texture never committed
 * to -- the art's own boxes were 100x92 on an irregular pitch, which no honest hit-test could have
 * matched.
 *
 * <p>Cells are square because the things in them are: a vanilla item is 16x16, and a Pokemon
 * profile render is squared off too. Sized as a share of the panel rather than in pixels, for the
 * same reason the reveal screen's row is -- if it fits at one window size it fits at all of them.
 *
 * <p>Kept free of Minecraft types so the arithmetic can be tested, and rendered offline, without a
 * client. Every geometry defect this project has shipped was found that way.
 */
record ShopGridLayout(int x, int y, int cell, int gap, int columns, int rows) {

    /** Gap between cells, as a share of the cell. Tuned against the frame's 879x790 panel. */
    private static final float GAP_OF_CELL = 0.09f;
    /** Breathing room between the outermost cells and the panel's inner edge. */
    private static final float PAD_OF_PANEL = 0.008f;
    /** Vanilla draws an item at 16x16; a cell is scaled from that. */
    static final int ITEM_PIXELS = 16;
    /** Below this a cell shows nothing legible, so an absurd window draws no grid at all. */
    private static final int MIN_CELL = 6;

    /**
     * @param panelWidth  the panel's width after the frame has been scaled to the window
     * @return null when the panel is too small to hold the grid at a legible size
     */
    static ShopGridLayout of(int panelX, int panelY, int panelWidth, int panelHeight,
                             int columns, int rows) {
        if (columns <= 0 || rows <= 0 || panelWidth <= 0 || panelHeight <= 0) return null;

        int pad = Math.round(Math.min(panelWidth, panelHeight) * PAD_OF_PANEL);
        int usableWidth = panelWidth - 2 * pad;
        int usableHeight = panelHeight - 2 * pad;
        if (usableWidth <= 0 || usableHeight <= 0) return null;

        // Seeded from the gapless fit, which is always an over-estimate, then stepped down one
        // pixel at a time. Deliberately not one multiply: rounding a scaled cell and a scaled gap
        // both upward can leave the grid wider than what it was squeezed into, which is exactly
        // how the reveal screen's twenty-slot row overflowed by four pixels.
        int cell = Math.min(usableWidth / columns, usableHeight / rows);
        if (cell < MIN_CELL) return null;
        int gap = gapFor(cell);
        while (cell > MIN_CELL && !fits(cell, gap, columns, rows, usableWidth, usableHeight)) {
            cell--;
            gap = gapFor(cell);
        }
        // Last resort before giving up: a grid with no gaps at all still reads as a grid.
        if (!fits(cell, gap, columns, rows, usableWidth, usableHeight)) {
            gap = 0;
            if (!fits(cell, gap, columns, rows, usableWidth, usableHeight)) return null;
        }
        // MIN_CELL bounds the loop, but it has to be a floor as well: without this a 20x20 panel
        // walks straight past the loop and returns a grid of two-pixel cells.
        if (cell < MIN_CELL) return null;

        int x = panelX + (panelWidth - span(cell, gap, columns)) / 2;
        int y = panelY + (panelHeight - span(cell, gap, rows)) / 2;
        return new ShopGridLayout(x, y, cell, gap, columns, rows);
    }

    private static int gapFor(int cell) {
        return Math.max(1, Math.round(cell * GAP_OF_CELL));
    }

    private static int span(int cell, int gap, int count) {
        return count * cell + (count - 1) * gap;
    }

    private static boolean fits(int cell, int gap, int columns, int rows, int width, int height) {
        return span(cell, gap, columns) <= width && span(cell, gap, rows) <= height;
    }

    /** How many cells this page can show. */
    int slotCount() {
        return columns * rows;
    }

    int columnOf(int index) {
        return index % columns;
    }

    int rowOf(int index) {
        return index / columns;
    }

    /** Left edge of cell {@code index}, counting left to right then top to bottom. */
    int cellX(int index) {
        return x + columnOf(index) * (cell + gap);
    }

    /** Top edge of cell {@code index}. */
    int cellY(int index) {
        return y + rowOf(index) * (cell + gap);
    }

    int width() {
        return span(cell, gap, columns);
    }

    int height() {
        return span(cell, gap, rows);
    }

    /** The scale a 16x16 item is drawn at to fill a cell, leaving a little padding. */
    float itemScale() {
        return cell * 0.62f / ITEM_PIXELS;
    }

    /** True when the point is inside cell {@code index}, for hover and tooltips. */
    boolean hits(int index, double pointX, double pointY) {
        if (index < 0 || index >= slotCount()) return false;
        int left = cellX(index);
        int top = cellY(index);
        return pointX >= left && pointX < left + cell && pointY >= top && pointY < top + cell;
    }

    /**
     * Which cell the point is over, or -1 for the gaps between them and everything outside.
     *
     * <p>Resolved by arithmetic rather than by walking every cell: a click must not land on a
     * neighbour because the loop was off by a gap, and a miss in the gutter must stay a miss.
     */
    int slotAt(double pointX, double pointY) {
        int pitch = cell + gap;
        double localX = pointX - x;
        double localY = pointY - y;
        if (localX < 0 || localY < 0) return -1;

        int column = (int) (localX / pitch);
        int row = (int) (localY / pitch);
        if (column >= columns || row >= rows) return -1;
        // Inside the cell's pitch but past its box means the gutter after it.
        if (localX - column * pitch >= cell || localY - row * pitch >= cell) return -1;
        return row * columns + column;
    }
}
