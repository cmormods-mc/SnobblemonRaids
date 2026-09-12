package com.cobbleraids.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sliced GUI's layout arithmetic, in Minecraft's logical pixels.
 *
 * <p>This came in as an asset kit with its own claims about the three window sizes it supports.
 * The claims were checked against the assembled artwork before any of it was wired up -- all 64
 * slot centres land on slot art at 18, 20 and 22 pixels -- and these tests hold the arithmetic to
 * the same numbers from here on, because the hitboxes and the picture agreeing is the whole point
 * of the kit.
 */
class RaidGuiLayoutTest {

    private static RaidGuiLayout.Layout fit(int width, int height) {
        Optional<RaidGuiLayout.Layout> layout = RaidGuiLayout.fit(width, height);
        assertTrue(layout.isPresent(), "no layout for a " + width + "x" + height + " viewport");
        return layout.get();
    }

    @Test
    @DisplayName("a roomy viewport gets the largest cell, and the documented window size")
    void largestMode() {
        RaidGuiLayout.Layout layout = fit(640, 480);

        assertEquals(22, layout.slotSize());
        assertEquals(235, layout.frame().width());
        assertEquals(255, layout.frame().height());
        assertEquals(64, layout.slots().size());
    }

    @Test
    @DisplayName("the three supported modes are 18, 20 and 22 pixel cells and nothing between")
    void everyModeIsEven() {
        // The art is sliced for those three sizes; an odd cell would put the slot tile on a
        // half-pixel boundary and the grid would stop lining up with the outline drawn behind it.
        for (int width = 219; width <= 900; width += 1) {
            for (int height = 239; height <= 700; height += 7) {
                RaidGuiLayout.Layout layout = RaidGuiLayout.fit(width, height).orElse(null);
                if (layout == null) continue;
                int slot = layout.slotSize();
                assertTrue(slot == 18 || slot == 20 || slot == 22, "unexpected cell size " + slot);
            }
        }
    }

    @Test
    @DisplayName("the window steps down as the viewport shrinks, and gives up below the minimum")
    void stepsDown() {
        assertEquals(22, fit(320, 300).slotSize());
        assertEquals(20, fit(320, 256).slotSize());
        assertEquals(18, fit(320, 240).slotSize());
        // 320x240 is the smallest viewport Minecraft will produce; one pixel less and the last row
        // would be clipped, so an empty result is the honest answer.
        assertTrue(RaidGuiLayout.fit(320, 238).isEmpty());
        assertTrue(RaidGuiLayout.fit(218, 480).isEmpty());
        assertTrue(RaidGuiLayout.fit(0, 0).isEmpty());
        assertTrue(RaidGuiLayout.fit(-40, -40).isEmpty(), "a nonsense viewport must not throw");
    }

    @Test
    @DisplayName("the window always fits inside the viewport it was given")
    void neverOverflowsTheViewport() {
        // The failure this guards against is silent: a window a few pixels too wide simply has its
        // right-hand column of cells off the edge, still clickable, still invisible.
        for (int width = 220; width <= 1920; width += 13) {
            for (int height = 240; height <= 1080; height += 11) {
                RaidGuiLayout.Layout layout = RaidGuiLayout.fit(width, height).orElse(null);
                if (layout == null) continue;
                RaidGuiLayout.Rect frame = layout.frame();
                assertTrue(frame.x() >= 0 && frame.y() >= 0,
                        "window starts off-screen at " + width + "x" + height);
                assertTrue(frame.x() + frame.width() <= width,
                        "window is wider than " + width + " at " + width + "x" + height);
                assertTrue(frame.y() + frame.height() <= height,
                        "window is taller than " + height + " at " + width + "x" + height);
            }
        }
    }

    @Test
    @DisplayName("cells are square, evenly pitched, and sit inside the grid")
    void cellGeometry() {
        for (int slot : new int[] {18, 20, 22}) {
            RaidGuiLayout.Layout layout = modeWith(slot);
            RaidGuiLayout.Rect grid = layout.grid();

            assertEquals(64, layout.slots().size());
            for (int index = 0; index < 64; index++) {
                RaidGuiLayout.Rect cell = layout.slots().get(index);
                assertEquals(slot, cell.width());
                assertEquals(slot, cell.height());
                assertEquals(grid.x() + (index % 8) * (slot + 1), cell.x());
                assertEquals(grid.y() + (index / 8) * (slot + 1), cell.y());
                assertTrue(cell.x() + cell.width() <= grid.x() + grid.width());
                assertTrue(cell.y() + cell.height() <= grid.y() + grid.height());
            }
        }
    }

    @Test
    @DisplayName("the grid sits centred in the frame, which is what makes the art line up")
    void gridIsCentred() {
        RaidGuiLayout.Layout layout = fit(640, 480);
        RaidGuiLayout.Rect frame = layout.frame();
        RaidGuiLayout.Rect grid = layout.grid();

        int left = grid.x() - frame.x();
        int right = (frame.x() + frame.width()) - (grid.x() + grid.width());
        assertEquals(left, right, "grid is off-centre horizontally");
        // Vertically it is deliberately not centred: the header takes more room than the footer.
        assertEquals(40, grid.y() - frame.y());
        assertEquals(32, (frame.y() + frame.height()) - (grid.y() + grid.height()));
    }

    @Test
    @DisplayName("every cell's centre hit-tests back to that cell")
    void hitTestRoundTrips() {
        for (int slot : new int[] {18, 20, 22}) {
            RaidGuiLayout.Layout layout = modeWith(slot);
            for (int index = 0; index < 64; index++) {
                RaidGuiLayout.Rect cell = layout.slots().get(index);
                assertEquals(index, layout.slotAt(cell.x() + slot / 2.0, cell.y() + slot / 2.0));
                assertEquals(index, layout.slotAt(cell.x(), cell.y()), "top-left corner of " + index);
                assertEquals(index, layout.slotAt(cell.x() + slot - 1, cell.y() + slot - 1));
            }
        }
    }

    @Test
    @DisplayName("the one-pixel gaps between cells swallow clicks")
    void guttersAreMisses() {
        RaidGuiLayout.Layout layout = fit(640, 480);
        int slot = layout.slotSize();
        RaidGuiLayout.Rect grid = layout.grid();

        for (int column = 0; column < 7; column++) {
            int gutterX = grid.x() + column * (slot + 1) + slot;
            assertEquals(-1, layout.slotAt(gutterX, grid.y() + 3), "column gutter " + column);
        }
        for (int row = 0; row < 7; row++) {
            int gutterY = grid.y() + row * (slot + 1) + slot;
            assertEquals(-1, layout.slotAt(grid.x() + 3, gutterY), "row gutter " + row);
        }
    }

    @Test
    @DisplayName("points outside the grid are misses")
    void outsideIsAMiss() {
        RaidGuiLayout.Layout layout = fit(640, 480);
        RaidGuiLayout.Rect grid = layout.grid();

        assertEquals(-1, layout.slotAt(grid.x() - 1, grid.y() + 4));
        assertEquals(-1, layout.slotAt(grid.x() + 4, grid.y() - 1));
        assertEquals(-1, layout.slotAt(grid.x() + grid.width(), grid.y() + 4));
        assertEquals(-1, layout.slotAt(grid.x() + 4, grid.y() + grid.height()));
        assertEquals(-1, layout.slotAt(-5000, -5000));
    }

    @Test
    @DisplayName("the arrows and the balance button stay inside the frame and apart from each other")
    void chromeIsInsideTheFrame() {
        for (int slot : new int[] {18, 20, 22}) {
            RaidGuiLayout.Layout layout = modeWith(slot);
            RaidGuiLayout.Rect frame = layout.frame();
            for (RaidGuiLayout.Rect rect :
                    new RaidGuiLayout.Rect[] {layout.previous(), layout.next(), layout.button()}) {
                assertTrue(rect.x() >= frame.x() && rect.y() >= frame.y(), "chrome starts outside");
                assertTrue(rect.x() + rect.width() <= frame.x() + frame.width(), "chrome runs wide");
                assertTrue(rect.y() + rect.height() <= frame.y() + frame.height(), "chrome runs tall");
            }
            // Two arrows that overlapped would make one of them unclickable.
            assertTrue(layout.previous().x() + layout.previous().width() < layout.next().x());
            // And neither may sit over a cell.
            assertEquals(-1, layout.slotAt(layout.previous().x() + 1, layout.previous().y() + 1));
            assertEquals(-1, layout.slotAt(layout.next().x() + 1, layout.next().y() + 1));
            assertEquals(-1, layout.slotAt(layout.button().x() + 1, layout.button().y() + 1));
        }
    }

    @Test
    @DisplayName("a cell always has room for an unscaled 16x16 item icon")
    void itemsFit() {
        for (int slot : new int[] {18, 20, 22}) {
            assertEquals(slot, modeWith(slot).slots().get(0).width());
            assertTrue(slot >= 16, "a " + slot + "px cell cannot hold a 16px icon");
        }
    }

    /** The smallest viewport that yields the requested cell size, so each mode is really exercised. */
    private static RaidGuiLayout.Layout modeWith(int slot) {
        for (int height = 239; height <= 800; height++) {
            RaidGuiLayout.Layout layout = RaidGuiLayout.fit(1920, height).orElse(null);
            if (layout != null && layout.slotSize() == slot) return layout;
        }
        assertNotNull(null, "no viewport produced a " + slot + " pixel cell");
        return null;
    }

    // --- the Pokemon grid ----------------------------------------------------------------------

    private static RaidGuiLayout.Layout fitPokemon(int width, int height) {
        Optional<RaidGuiLayout.Layout> layout = RaidGuiLayout.fit(
                width, height, RaidGuiLayout.POKEMON_COLUMNS, RaidGuiLayout.POKEMON_ROWS);
        assertTrue(layout.isPresent(), "no Pokemon layout for " + width + "x" + height);
        return layout.get();
    }

    @Test
    @DisplayName("the Pokemon grid changes the cells and never the window")
    void pokemonGridKeepsTheWindow() {
        // The frame moving when a player pages from items to Pokemon would be worse than either
        // cell size, so this is the property that matters most about the second grid.
        for (int[] viewport : new int[][] {{640, 480}, {320, 300}, {320, 256}, {320, 240}}) {
            RaidGuiLayout.Layout items = fit(viewport[0], viewport[1]);
            RaidGuiLayout.Layout pokemon = fitPokemon(viewport[0], viewport[1]);

            assertEquals(items.frame(), pokemon.frame(), "frame at " + viewport[0] + "x" + viewport[1]);
            assertEquals(items.grid(), pokemon.grid(), "grid rect");
            assertEquals(items.panel(), pokemon.panel(), "panel");
            assertEquals(items.button(), pokemon.button(), "button");
        }
    }

    @Test
    @DisplayName("a Pokemon cell is big enough for 48x32 art to stay legible")
    void pokemonCellsAreLargeEnough() {
        for (int[] viewport : new int[][] {{640, 480}, {320, 300}, {320, 256}, {320, 240}}) {
            RaidGuiLayout.Layout pokemon = fitPokemon(viewport[0], viewport[1]);
            assertEquals(16, pokemon.slots().size());
            // 32 is where the card art stops being a smear; the smallest window must still clear
            // the 22 that demonstrably does not work.
            assertTrue(pokemon.slotSize() >= 32,
                    "cell " + pokemon.slotSize() + " at " + viewport[0] + "x" + viewport[1]);
            assertTrue(pokemon.slotSize() > fit(viewport[0], viewport[1]).slotSize());
        }
    }

    @Test
    @DisplayName("Pokemon cells tile their grid exactly, with no slack at the edge")
    void pokemonCellsFillTheGrid() {
        RaidGuiLayout.Layout layout = fitPokemon(640, 480);
        int cell = layout.slotSize();
        int span = RaidGuiLayout.POKEMON_COLUMNS * cell
                 + RaidGuiLayout.GAP * (RaidGuiLayout.POKEMON_COLUMNS - 1);
        assertEquals(layout.grid().width(), span, "cells plus gaps must fill the grid rect");
        for (RaidGuiLayout.Rect slot : layout.slots()) {
            assertTrue(slot.x() >= layout.grid().x()
                    && slot.x() + slot.width() <= layout.grid().x() + layout.grid().width());
            assertTrue(slot.y() >= layout.grid().y()
                    && slot.y() + slot.height() <= layout.grid().y() + layout.grid().height());
        }
    }

    @Test
    @DisplayName("every Pokemon cell hit-tests back to itself, and the gaps still swallow clicks")
    void pokemonHitTesting() {
        RaidGuiLayout.Layout layout = fitPokemon(640, 480);
        int cell = layout.slotSize();
        for (int index = 0; index < layout.slots().size(); index++) {
            RaidGuiLayout.Rect slot = layout.slots().get(index);
            assertEquals(index, layout.slotAt(slot.x() + cell / 2.0, slot.y() + cell / 2.0));
            assertEquals(index, layout.slotAt(slot.x(), slot.y()), "top-left of " + index);
            assertEquals(index, layout.slotAt(slot.x() + cell - 1, slot.y() + cell - 1));
        }
        // The gap to the right of the first cell belongs to nothing, exactly as in the item grid.
        RaidGuiLayout.Rect first = layout.slots().get(0);
        assertEquals(-1, layout.slotAt(first.x() + cell, first.y() + cell / 2.0));
    }
}
