package com.cobbleraids.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure layout in Minecraft logical GUI pixels. Recompute in Screen.init(). */
public final class RaidGuiLayout {
    private RaidGuiLayout() {}
    public static final int ROWS = 8, COLUMNS = 8, GAP = 1;
    public static final int MIN_SLOT = 18, PREFERRED_SLOT = 22, MARGIN = 8;

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }

    public record Layout(Rect frame, Rect panel, Rect grid, List<Rect> slots,
                         Rect previous, Rect next, Rect button, int slotSize) {
        public Layout { slots = List.copyOf(slots); }
        public int slotAt(double mouseX, double mouseY) {
            if (!grid.contains(mouseX, mouseY)) return -1;
            int localX = (int) Math.floor(mouseX - grid.x());
            int localY = (int) Math.floor(mouseY - grid.y());
            int pitch = slotSize + GAP;
            int col = localX / pitch, row = localY / pitch;
            if (col >= COLUMNS || row >= ROWS || localX % pitch >= slotSize || localY % pitch >= slotSize) return -1;
            return row * COLUMNS + col;
        }
    }

    public static Optional<Layout> fit(int guiWidth, int guiHeight) {
        int available = Math.min(PREFERRED_SLOT,
            Math.min(Math.floorDiv(guiWidth - MARGIN * 2 - 59, 8),
                     Math.floorDiv(guiHeight - MARGIN * 2 - 79, 8)));
        int slot = available - Math.floorMod(available, 2);
        if (slot < MIN_SLOT) return Optional.empty();
        int grid = slot * COLUMNS + GAP * (COLUMNS - 1);
        int width = grid + 52, height = grid + 72;
        int x = (guiWidth - width) / 2, y = (guiHeight - height) / 2;
        List<Rect> slots = new ArrayList<>(64);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                slots.add(new Rect(x + 26 + col * (slot + GAP),
                                   y + 40 + row * (slot + GAP), slot, slot));
            }
        }
        return Optional.of(new Layout(new Rect(x,y,width,height),
            new Rect(x+20,y+34,grid+12,grid+12), new Rect(x+26,y+40,grid,grid), slots,
            new Rect(x+width/2-40,y+23,14,12), new Rect(x+width/2+26,y+23,14,12),
            new Rect(x+(width-82)/2,y+height-21,82,11), slot));
    }
}
