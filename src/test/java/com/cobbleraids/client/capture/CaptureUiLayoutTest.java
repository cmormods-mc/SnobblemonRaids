package com.cobbleraids.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure layout math, no Minecraft GUI classes involved -- same convention as TrophyGalleryLayoutTest. */
class CaptureUiLayoutTest {

    private static final int[] WIDTHS = {320, 480, 640, 960, 1920};
    private static final int[] HEIGHTS = {240, 270, 360, 540, 1080};

    @Test
    @DisplayName("the layout stays inside the screen and usable across every GUI Scale this game supports")
    void layoutFitsAcrossGuiScales() {
        for (int screenWidth : WIDTHS) {
            for (int screenHeight : HEIGHTS) {
                CaptureUiLayout layout = CaptureUiLayout.fit(screenWidth, screenHeight);

                assertTrue(layout.usable(), "unusable at " + screenWidth + "x" + screenHeight);
                assertTrue(layout.x() >= 0 && layout.y() >= 0);
                assertTrue(layout.x() + layout.width() <= screenWidth);
                assertTrue(layout.y() + layout.height() <= screenHeight);
                assertTrue(layout.chamberWidth() > 0 && layout.contentHeight() > 0);
                assertTrue(layout.contentY() + layout.contentHeight() < layout.trackY() - 10);
                assertTrue(layout.trackY() + 22 < layout.buttonY());
                assertTrue(layout.buttonY() + 24 < layout.y() + layout.height());
            }
        }
    }

    @Test
    @DisplayName("the timing sweep is repeatable and its zones stay centered on the track")
    void sweepIsRepeatableAndZonesStayCentered() {
        assertEquals(0, CaptureUiLayout.bounce(0, 1000));
        assertEquals(0.5, CaptureUiLayout.bounce(500, 1000));
        assertEquals(1, CaptureUiLayout.bounce(1000, 1000));
        assertEquals(0.5, CaptureUiLayout.bounce(1500, 1000));
        assertEquals(0, CaptureUiLayout.bounce(2000, 1000));

        CaptureUiLayout layout = CaptureUiLayout.fit(960, 540);
        assertEquals(layout.trackWidth() / 2, layout.zoneWidth(500, 1000), 1);
        assertEquals(layout.trackX(), layout.indicator(-1));
        assertEquals(layout.trackX() + layout.trackWidth(), layout.indicator(2));
    }
}
