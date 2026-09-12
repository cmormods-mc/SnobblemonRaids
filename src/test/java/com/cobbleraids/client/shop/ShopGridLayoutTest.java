package com.cobbleraids.client.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shop grid's arithmetic, checked without a client.
 *
 * <p>The cases that matter are the two this project has actually been bitten by: a grid that
 * overflows the space it was squeezed into because rounding pushed both the cell and the gap up,
 * and a hit-test that hands a click to the wrong cell. Both are invisible to a running server --
 * the purchase still goes through, it is simply for the wrong thing.
 */
class ShopGridLayoutTest {

    // The panel measured out of the frame art, in its own pixels.
    private static final int PANEL_X = 141;
    private static final int PANEL_Y = 189;
    private static final int PANEL_W = 879;
    private static final int PANEL_H = 790;
    private static final int COLUMNS = 9;
    private static final int ROWS = 8;

    private static ShopGridLayout shipped() {
        return ShopGridLayout.of(PANEL_X, PANEL_Y, PANEL_W, PANEL_H, COLUMNS, ROWS);
    }

    @Test
    @DisplayName("the shipped panel lays out 72 square cells at the geometry the art was measured for")
    void shippedGeometry() {
        ShopGridLayout layout = shipped();

        assertNotNull(layout);
        assertEquals(89, layout.cell());
        assertEquals(8, layout.gap());
        assertEquals(72, layout.slotCount());
        assertEquals(148, layout.x());
        assertEquals(200, layout.y());
    }

    @Test
    @DisplayName("the grid is centred in the panel on both axes")
    void centred() {
        ShopGridLayout layout = shipped();

        int leftMargin = layout.x() - PANEL_X;
        int rightMargin = (PANEL_X + PANEL_W) - (layout.x() + layout.width());
        int topMargin = layout.y() - PANEL_Y;
        int bottomMargin = (PANEL_Y + PANEL_H) - (layout.y() + layout.height());

        assertTrue(Math.abs(leftMargin - rightMargin) <= 1, leftMargin + " vs " + rightMargin);
        assertTrue(Math.abs(topMargin - bottomMargin) <= 1, topMargin + " vs " + bottomMargin);
    }

    @Test
    @DisplayName("the grid never overflows its panel, at any size a window can produce")
    void neverOverflows() {
        // The regression guard. A single scale-and-round pass passes a spot check and still
        // overflows four pixels wide somewhere in the middle of the range, so sweep the range.
        for (int width = 120; width <= 1600; width += 7) {
            for (int height = 120; height <= 1400; height += 11) {
                ShopGridLayout layout =
                        ShopGridLayout.of(0, 0, width, height, COLUMNS, ROWS);
                if (layout == null) continue;
                assertTrue(layout.width() <= width,
                        "grid " + layout.width() + " wide in a " + width + "x" + height + " panel");
                assertTrue(layout.height() <= height,
                        "grid " + layout.height() + " tall in a " + width + "x" + height + " panel");
            }
        }
    }

    @Test
    @DisplayName("every cell's centre hit-tests back to that cell")
    void hitTestRoundTrips() {
        ShopGridLayout layout = shipped();

        for (int index = 0; index < layout.slotCount(); index++) {
            double centreX = layout.cellX(index) + layout.cell() / 2.0;
            double centreY = layout.cellY(index) + layout.cell() / 2.0;

            assertEquals(index, layout.slotAt(centreX, centreY));
            assertTrue(layout.hits(index, centreX, centreY));
        }
    }

    @Test
    @DisplayName("corners of a cell belong to it, and the pixel past it does not")
    void hitTestEdges() {
        ShopGridLayout layout = shipped();
        int index = 13;
        int left = layout.cellX(index);
        int top = layout.cellY(index);
        int cell = layout.cell();

        assertEquals(index, layout.slotAt(left, top));
        assertEquals(index, layout.slotAt(left + cell - 1, top + cell - 1));
        // One past the right edge is the gutter, not the next cell along.
        assertEquals(-1, layout.slotAt(left + cell, top));
        assertEquals(-1, layout.slotAt(left, top + cell));
    }

    @Test
    @DisplayName("the gutters between cells swallow clicks rather than handing them to a neighbour")
    void guttersAreMisses() {
        ShopGridLayout layout = shipped();

        for (int column = 0; column < COLUMNS - 1; column++) {
            int gutterX = layout.x() + column * (layout.cell() + layout.gap()) + layout.cell();
            for (int offset = 0; offset < layout.gap(); offset++) {
                assertEquals(-1, layout.slotAt(gutterX + offset, layout.y() + 4),
                        "column gutter " + column + " offset " + offset);
            }
        }
    }

    @Test
    @DisplayName("points outside the grid are misses, including above and left of it")
    void outsideIsAMiss() {
        ShopGridLayout layout = shipped();

        assertEquals(-1, layout.slotAt(layout.x() - 1, layout.y() + 10));
        assertEquals(-1, layout.slotAt(layout.x() + 10, layout.y() - 1));
        assertEquals(-1, layout.slotAt(layout.x() + layout.width() + 1, layout.y() + 10));
        assertEquals(-1, layout.slotAt(layout.x() + 10, layout.y() + layout.height() + 1));
        assertEquals(-1, layout.slotAt(-5000, -5000));
    }

    @Test
    @DisplayName("index maps to the row and column it is drawn at")
    void indexMapping() {
        ShopGridLayout layout = shipped();

        assertEquals(0, layout.rowOf(0));
        assertEquals(0, layout.columnOf(0));
        assertEquals(0, layout.rowOf(COLUMNS - 1));
        assertEquals(COLUMNS - 1, layout.columnOf(COLUMNS - 1));
        assertEquals(1, layout.rowOf(COLUMNS));
        assertEquals(0, layout.columnOf(COLUMNS));
        assertEquals(ROWS - 1, layout.rowOf(layout.slotCount() - 1));
        // Cells on the same row share a top edge; cells in a column share a left edge.
        assertEquals(layout.cellY(0), layout.cellY(COLUMNS - 1));
        assertEquals(layout.cellX(0), layout.cellX(COLUMNS));
    }

    @Test
    @DisplayName("a panel too small for a legible grid returns nothing rather than a garbage one")
    void tooSmallReturnsNull() {
        assertNull(ShopGridLayout.of(0, 0, 20, 20, COLUMNS, ROWS));
        assertNull(ShopGridLayout.of(0, 0, 0, 500, COLUMNS, ROWS));
        assertNull(ShopGridLayout.of(0, 0, 500, 500, 0, ROWS));
        assertNull(ShopGridLayout.of(0, 0, 500, 500, COLUMNS, -1));
    }

    @Test
    @DisplayName("an item is scaled to sit inside its cell, never over the edge")
    void itemFitsInsideItsCell() {
        ShopGridLayout layout = shipped();

        float drawn = ShopGridLayout.ITEM_PIXELS * layout.itemScale();
        assertTrue(drawn < layout.cell(), drawn + " drawn into a " + layout.cell() + "px cell");
        assertTrue(drawn > layout.cell() * 0.4f, "item is too small to read: " + drawn);
    }
}
