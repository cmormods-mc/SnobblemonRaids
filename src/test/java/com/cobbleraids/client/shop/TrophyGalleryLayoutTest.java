package com.cobbleraids.client.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that every card the layout hands out actually fits inside the panel it computed, across the
 * range of window sizes and GUI scales a real player might have -- the same property
 * {@code RaidGuiLayout} is exercised against, and the same reason: a layout bug here is invisible in
 * a screenshot at one size and obvious the moment someone changes their GUI Scale.
 */
class TrophyGalleryLayoutTest {

    private static final int[] SCREEN_WIDTHS = {854, 1280, 1920, 2560, 3840};
    private static final int[] SCREEN_HEIGHTS = {480, 720, 1080, 1440, 2160};

    @Test
    @DisplayName("every card stays within the panel, across common resolutions and every GUI scale")
    void cardsStayOnScreenAcrossWindowSizesAndGuiScales() {
        for (int screenWidth : SCREEN_WIDTHS) {
            for (int screenHeight : SCREEN_HEIGHTS) {
                for (int scale = 1; scale <= 6; scale++) {
                    // What Minecraft actually hands a Screen: physical pixels divided by GUI Scale,
                    // rounded up -- the same conversion RaidGuiLayoutTest's own sweep uses.
                    int logicalWidth = (screenWidth + scale - 1) / scale;
                    int logicalHeight = (screenHeight + scale - 1) / scale;
                    assertLayoutFitsOrIsUnusable(logicalWidth, logicalHeight);
                }
            }
        }
    }

    private static void assertLayoutFitsOrIsUnusable(int width, int height) {
        TrophyGalleryLayout layout = TrophyGalleryLayout.fit(width, height);
        if (!layout.usable()) return;

        assertTrue(layout.x() >= 0 && layout.y() >= 0, size(width, height) + ": panel must not start off-screen");
        assertTrue(layout.x() + layout.width() <= width && layout.y() + layout.height() <= height,
                size(width, height) + ": panel must not extend past the screen");
        assertTrue(layout.bodyHeight() >= 106, size(width, height) + ": body too short to show a readable case");
        for (int i = 0; i < layout.columns(); i++) {
            assertTrue(layout.cardX(i) + layout.cardWidth(i) <= layout.x() + layout.width(),
                    size(width, height) + ": card " + i + " overruns the panel");
        }
        assertTrue(layout.bodyY() + layout.bodyHeight() < layout.y() + layout.height() - 25,
                size(width, height) + ": body must leave room for the footer row");
    }

    private static String size(int width, int height) {
        return width + "x" + height;
    }

    @Test
    @DisplayName("column count adapts to width instead of shrinking every card's text to fit")
    void columnCountAdaptsToWidth() {
        assertEquals(3, TrophyGalleryLayout.fit(960, 540).columns());
        assertEquals(2, TrophyGalleryLayout.fit(480, 270).columns());
        assertEquals(1, TrophyGalleryLayout.fit(320, 240).columns());
        assertFalse(TrophyGalleryLayout.fit(320, 180).usable(), "too small for even a single readable case");
    }

    @Test
    @DisplayName("a window shaped nothing like the art never stretches the panel off its own aspect ratio")
    void panelNeverStretchesOffTheArtsAspectRatio() {
        // A very wide, short window and a very tall, narrow one -- exactly the shapes that would
        // stretch a panel sized independently on each axis.
        assertAspectRatioPreserved(TrophyGalleryLayout.fit(3840, 700));
        assertAspectRatioPreserved(TrophyGalleryLayout.fit(700, 2160));
        assertAspectRatioPreserved(TrophyGalleryLayout.fit(1000, 1000));
    }

    private static void assertAspectRatioPreserved(TrophyGalleryLayout layout) {
        if (!layout.usable() || layout.width() < 500) return; // the 1-/2-column fallback is not painted art
        double artRatio = TrophyGalleryLayout.TEXTURE_WIDTH / (double) TrophyGalleryLayout.TEXTURE_HEIGHT;
        double panelRatio = layout.width() / (double) layout.height();
        assertEquals(artRatio, panelRatio, 0.01, "the panel must keep the art's own aspect ratio");
    }
}
