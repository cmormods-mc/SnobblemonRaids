package com.cobbleraids.client.reveal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reveal screen's text never had to hold more than a handful of lines. The policy economy
 * grants up to six selections and a currency payout adds a seventh, which is taller than the
 * chamber at every window size below the maximum -- at the 360px minimum only three of seven fit,
 * so the rest were drawn past the bottom of the art meant to frame them.
 *
 * <p>Chamber geometry is computed the way the screen computes it: CHAMBER_RECT, 1172x675 native,
 * scaled by panelWidth / NATIVE_WIDTH within a 1672-wide design.
 */
class ResultTextLayoutTest {

    private static ResultTextLayout at(int panelWidth, int lineCount) {
        return ResultTextLayout.of(0, chamberHeight(panelWidth), chamberWidth(panelWidth), lineCount);
    }

    private static int chamberHeight(int panelWidth) {
        return Math.round(675 * (panelWidth / 1672f));
    }

    private static int chamberWidth(int panelWidth) {
        return Math.round(1172 * (panelWidth / 1672f));
    }

    @Test
    @DisplayName("a short result is left exactly where it always rendered")
    void shortResultIsUnchanged() {
        ResultTextLayout layout = at(640, 2);
        int historical = chamberHeight(640) / 2 + Math.round(chamberWidth(640) * 0.1f);

        assertEquals(historical, layout.top());
        assertTrue(layout.unscaled());
        assertEquals(ResultTextLayout.LINE_STEP, layout.step());
    }

    @Test
    @DisplayName("a full bundle stays inside the chamber at every window size")
    void fullBundleAlwaysFits() {
        for (int panelWidth = 360; panelWidth <= 640; panelWidth += 20) {
            for (int lines = 1; lines <= 7; lines++) {
                ResultTextLayout layout = at(panelWidth, lines);
                int bottom = layout.top() + layout.height(lines);

                assertTrue(bottom <= chamberHeight(panelWidth) + 1,
                        "panel " + panelWidth + " with " + lines + " lines ends at " + bottom
                                + ", past the chamber's " + chamberHeight(panelWidth));
            }
        }
    }

    @Test
    @DisplayName("text never rises above the chamber's centre")
    void neverRisesAboveCentre() {
        for (int panelWidth = 360; panelWidth <= 640; panelWidth += 20) {
            ResultTextLayout layout = at(panelWidth, 7);

            assertTrue(layout.top() >= chamberHeight(panelWidth) / 2,
                    "panel " + panelWidth + " puts text at " + layout.top()
                            + ", above the centre " + chamberHeight(panelWidth) / 2);
        }
    }

    @Test
    @DisplayName("the widest panel fits a full bundle without shrinking anything")
    void widestPanelNeedsNoScaling() {
        assertTrue(at(640, 7).unscaled(), "a maximised window should not need scaled text");
    }

    @Test
    @DisplayName("a narrow window scales rather than clipping, down to a readable floor")
    void narrowWindowScales() {
        ResultTextLayout layout = at(360, 7);

        assertFalse(layout.unscaled(), "seven lines cannot fit a 360px panel unscaled");
        assertTrue(layout.scale() >= 0.5f, "scaled to " + layout.scale() + ", past readability");
        assertTrue(layout.scale() < 1.0f);
    }

    @Test
    @DisplayName("no lines is not a division by zero")
    void emptyResultIsSafe() {
        ResultTextLayout layout = at(480, 0);

        assertTrue(layout.unscaled());
        assertEquals(0, layout.height(0));
    }
}
