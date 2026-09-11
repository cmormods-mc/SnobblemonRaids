package com.cobbleraids.client.reveal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The receipt row's geometry, which replaced a list of text lines that stepped a fixed 12 pixels
 * whatever the window was doing. That list ran off the bottom of a small panel, and once that was
 * fixed it sat on top of the Poke Ball instead. A row sized as a share of its chamber cannot do
 * either, and these are the tests that hold it to that.
 *
 * <p>Chamber geometry matches the screen's: CHAMBER_RECT, 1172x675 native, scaled by
 * panelWidth / 1672.
 */
class RewardSlotLayoutTest {

    private static final int[] PANEL_WIDTHS = {360, 400, 440, 480, 520, 560, 600, 640};

    private static int chamberX(int panelWidth) {
        return Math.round(420 * (panelWidth / 1672f));
    }

    private static int chamberY(int panelWidth) {
        return Math.round(112 * (panelWidth / 1672f));
    }

    private static int chamberWidth(int panelWidth) {
        return Math.round(1172 * (panelWidth / 1672f));
    }

    private static int chamberHeight(int panelWidth) {
        return Math.round(675 * (panelWidth / 1672f));
    }

    private static RewardSlotLayout at(int panelWidth, int count) {
        return RewardSlotLayout.of(chamberX(panelWidth), chamberY(panelWidth),
                chamberWidth(panelWidth), chamberHeight(panelWidth), count);
    }

    @Test
    @DisplayName("a full claim's row fits inside the chamber at every window size")
    void fullRowFitsEverywhere() {
        for (int panelWidth : PANEL_WIDTHS) {
            // Six selections plus a payout chip: the case the text list could not hold.
            RewardSlotLayout slots = at(panelWidth, 7);
            int right = slots.slotX(6) + slots.cell();
            int bottom = slots.y() + slots.cell();

            assertTrue(slots.x() >= chamberX(panelWidth),
                    "panel " + panelWidth + " starts the row at " + slots.x()
                            + ", left of the chamber's " + chamberX(panelWidth));
            assertTrue(right <= chamberX(panelWidth) + chamberWidth(panelWidth),
                    "panel " + panelWidth + " ends the row at " + right + ", past the chamber");
            assertTrue(bottom <= chamberY(panelWidth) + chamberHeight(panelWidth),
                    "panel " + panelWidth + " ends the row at " + bottom + ", below the chamber");
            assertTrue(slots.y() >= chamberY(panelWidth), "the row starts above the chamber");
        }
    }

    @Test
    @DisplayName("the row is centred in the chamber")
    void rowIsCentred() {
        for (int panelWidth : PANEL_WIDTHS) {
            RewardSlotLayout slots = at(panelWidth, 7);
            int leftGap = slots.x() - chamberX(panelWidth);
            int rightGap = chamberX(panelWidth) + chamberWidth(panelWidth) - (slots.x() + slots.width());

            assertTrue(Math.abs(leftGap - rightGap) <= 1,
                    "panel " + panelWidth + " leaves " + leftGap + " left and " + rightGap + " right");
        }
    }

    @Test
    @DisplayName("the row sits clear of the chamber floor rather than on it")
    void rowFloatsAboveTheFloor() {
        for (int panelWidth : PANEL_WIDTHS) {
            RewardSlotLayout slots = at(panelWidth, 7);
            int floorGap = chamberY(panelWidth) + chamberHeight(panelWidth) - (slots.y() + slots.cell());

            assertTrue(floorGap > 0, "panel " + panelWidth + " puts the row on the chamber floor");
        }
    }

    @Test
    @DisplayName("an unusually long row is squeezed rather than allowed to overflow")
    void longRowIsSqueezed() {
        // A hand-written definition could grant more than the policy's six. It still has to fit.
        for (int count : new int[] {8, 12, 20}) {
            RewardSlotLayout slots = at(360, count);
            int right = slots.slotX(count - 1) + slots.cell();

            assertTrue(right <= chamberX(360) + chamberWidth(360) + 1,
                    count + " slots end at " + right + ", past the chamber's right edge");
        }
    }

    @Test
    @DisplayName("slots scale with the window rather than staying a fixed size")
    void slotsScaleWithTheWindow() {
        // The defect this design exists to prevent: a fixed pixel size that fits one window only.
        assertTrue(at(640, 7).cell() > at(360, 7).cell(),
                "a wider panel should give bigger slots");
    }

    @Test
    @DisplayName("hit testing finds the slot under the pointer, and nothing between them")
    void hitTestingFindsSlots() {
        RewardSlotLayout slots = at(640, 7);

        for (int index = 0; index < 7; index++) {
            int centreX = slots.slotX(index) + slots.cell() / 2;
            int centreY = slots.y() + slots.cell() / 2;
            assertEquals(index, slots.slotAt(centreX, centreY));
        }
        assertEquals(-1, slots.slotAt(slots.x() - 5, slots.y() + slots.cell() / 2), "left of the row");
        assertEquals(-1, slots.slotAt(slots.x() + slots.cell() / 2, slots.y() - 5), "above the row");
        // The gap between two slots belongs to neither.
        assertEquals(-1, slots.slotAt(slots.slotX(0) + slots.cell() + 1, slots.y() + 1), "in a gap");
    }

    @Test
    @DisplayName("an item is drawn smaller than the slot that holds it")
    void itemFitsItsSlot() {
        for (int panelWidth : PANEL_WIDTHS) {
            RewardSlotLayout slots = at(panelWidth, 7);
            float drawn = RewardSlotLayout.ITEM_PIXELS * slots.itemScale();

            assertTrue(drawn < slots.cell(), "panel " + panelWidth + " draws a " + drawn
                    + "px item in a " + slots.cell() + "px slot");
        }
    }

    @Test
    @DisplayName("nothing granted is nothing to place")
    void emptyRowIsNull() {
        assertNull(at(640, 0));
    }
}
