package com.cobbleraids.client.reveal;

/**
 * Where the granted items sit in the reveal screen's chamber, as a row of slots.
 *
 * <p>Everything here is a fraction of the chamber rather than a pixel count, which is the whole
 * point. The text list this replaced stepped 12 pixels a line whatever the window was doing, so a
 * six-selection claim ran off the bottom of a small panel and, once that was fixed, sat on top of
 * the Poke Ball the chamber exists to show. A row sized as a share of its container takes the same
 * share at every window size: if it fits once it fits always.
 *
 * <p>Kept free of Minecraft types so the arithmetic is testable without a client, which is the
 * only way any of this screen's geometry has ever been checked.
 */
record RewardSlotLayout(int x, int y, int cell, int gap, int count) {

    /** Slot box as a share of chamber height. Eight of these stack inside one chamber. */
    private static final float CELL_OF_HEIGHT = 0.16f;
    /** Gap between slots, as a share of the slot. */
    private static final float GAP_OF_CELL = 0.28f;
    /** How far the row floats above the chamber floor, as a share of chamber height. */
    private static final float FLOOR_OF_HEIGHT = 0.17f;
    /** Vanilla draws an item at 16x16; a slot is scaled from that. */
    static final int ITEM_PIXELS = 16;
    /** Below this a slot is not worth drawing, so a truly absurd row clips instead of vanishing. */
    private static final int MIN_CELL = 5;

    /**
     * @param count how many slots to place, items plus a payout chip if there is one
     * @return null when there is nothing to place
     */
    static RewardSlotLayout of(int chamberX, int chamberY, int chamberWidth, int chamberHeight, int count) {
        if (count <= 0) return null;

        int cell = Math.max(MIN_CELL, Math.round(chamberHeight * CELL_OF_HEIGHT));
        int gap = Math.max(1, Math.round(cell * GAP_OF_CELL));

        // A long row is squeezed rather than allowed to overflow. A policy claim is now up to
        // nine selections -- one specialty, three key fragments and five general -- plus a payout
        // chip, and a legacy definition can hand out more than that, so the row must still fit.
        //
        // Shrunk by stepping down rather than by one multiply, because rounding a scaled cell and
        // a scaled gap both upward can leave the row wider than the space it was squeezed into --
        // twenty slots overflowed by four pixels that way, which a single pass cannot see.
        while (count * cell + (count - 1) * gap > chamberWidth && cell > MIN_CELL) {
            cell--;
            gap = Math.max(1, Math.round(cell * GAP_OF_CELL));
        }

        int total = count * cell + (count - 1) * gap;
        int x = chamberX + (chamberWidth - total) / 2;
        int y = chamberY + chamberHeight - Math.round(chamberHeight * FLOOR_OF_HEIGHT) - cell;
        return new RewardSlotLayout(x, y, cell, gap, count);
    }

    /** Left edge of slot {@code index}. */
    int slotX(int index) {
        return x + index * (cell + gap);
    }

    /** Total width the row occupies. */
    int width() {
        return count * cell + (count - 1) * gap;
    }

    /** The scale a 16x16 item is drawn at to fill a slot, leaving a little padding. */
    float itemScale() {
        return cell * 0.74f / ITEM_PIXELS;
    }

    /** True when the point is inside slot {@code index}, for tooltips. */
    boolean hits(int index, double pointX, double pointY) {
        int left = slotX(index);
        return pointX >= left && pointX < left + cell && pointY >= y && pointY < y + cell;
    }

    /** Which slot the point is over, or -1. */
    int slotAt(double pointX, double pointY) {
        for (int index = 0; index < count; index++) {
            if (hits(index, pointX, pointY)) return index;
        }
        return -1;
    }
}
