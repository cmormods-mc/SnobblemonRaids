package com.cobbleraids.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure layout in Minecraft logical GUI pixels. Recompute in Screen.init(). */
public final class RaidGuiLayout {
    private RaidGuiLayout() {}
    public static final int ROWS = 8, COLUMNS = 8, GAP = 1;
    /**
     * The grid a page of Pokemon uses instead: sixteen cells in the same square.
     *
     * <p>Not a preference. The card artwork is 48x32 and Minecraft blits it nearest, so at an
     * 18-22 pixel cell a 2.4x reduction destroys the silhouette -- measured against NEAREST, BOX
     * and LANCZOS, none of which helps, because the cell is what is wrong. Quartering the grid puts
     * a cell at 37 to 45 pixels, where the art is legible to crisp.
     */
    public static final int POKEMON_ROWS = 4, POKEMON_COLUMNS = 4;
    public static final int MIN_SLOT = 18, PREFERRED_SLOT = 22, MARGIN = 8;

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }

    public record Layout(Rect frame, Rect panel, Rect grid, List<Rect> slots,
                         Rect previous, Rect next, Rect button, int slotSize,
                         int columns, int rows) {
        public Layout { slots = List.copyOf(slots); }
        public int slotAt(double mouseX, double mouseY) {
            if (!grid.contains(mouseX, mouseY)) return -1;
            int localX = (int) Math.floor(mouseX - grid.x());
            int localY = (int) Math.floor(mouseY - grid.y());
            int pitch = slotSize + GAP;
            int col = localX / pitch, row = localY / pitch;
            if (col >= columns || row >= rows || localX % pitch >= slotSize || localY % pitch >= slotSize) return -1;
            return row * columns + col;
        }
    }

    public static Optional<Layout> fit(int guiWidth, int guiHeight) {
        return fit(guiWidth, guiHeight, COLUMNS, ROWS);
    }

    /**
     * The same window, subdivided differently.
     *
     * <p>The frame is always sized from the eight-by-eight geometry, whatever grid is asked for, so
     * paging from an item section to a Pokemon one changes the cells and not the window. A window
     * that resized under the cursor mid-page would be worse than either cell size.
     */
    public static Optional<Layout> fit(int guiWidth, int guiHeight, int columns, int rows) {
        if (columns < 1 || rows < 1) throw new IllegalArgumentException("grid must be at least 1x1");
        int available = Math.min(PREFERRED_SLOT,
            Math.min(Math.floorDiv(guiWidth - MARGIN * 2 - 59, 8),
                     Math.floorDiv(guiHeight - MARGIN * 2 - 79, 8)));
        int slot = available - Math.floorMod(available, 2);
        if (slot < MIN_SLOT) return Optional.empty();
        int grid = slot * COLUMNS + GAP * (COLUMNS - 1);
        int width = grid + 52, height = grid + 72;
        int x = (guiWidth - width) / 2, y = (guiHeight - height) / 2;
        // Reduces to `slot` at eight columns, so the ordinary grid is not a special case.
        int cell = (grid - GAP * (columns - 1)) / columns;
        List<Rect> slots = new ArrayList<>(columns * rows);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                slots.add(new Rect(x + 26 + col * (cell + GAP),
                                   y + 40 + row * (cell + GAP), cell, cell));
            }
        }
        return Optional.of(new Layout(new Rect(x,y,width,height),
            new Rect(x+20,y+34,grid+12,grid+12), new Rect(x+26,y+40,grid,grid), slots,
            new Rect(x+width/2-40,y+23,14,12), new Rect(x+width/2+26,y+23,14,12),
            new Rect(x+(width-82)/2,y+height-21,82,11), cell, columns, rows));
    }
}
