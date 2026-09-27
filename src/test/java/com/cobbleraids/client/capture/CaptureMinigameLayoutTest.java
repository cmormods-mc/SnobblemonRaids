package com.cobbleraids.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure layout math, no Minecraft GUI classes involved -- same convention as TrophyGalleryLayoutTest. */
class CaptureMinigameLayoutTest {

    @Test
    @DisplayName("the panel is centered and never exceeds the screen")
    void panelIsCenteredAndBounded() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(1280, 720);

        assertEquals(layout.panelX() + layout.panelWidth() / 2, 1280 / 2, 1);
        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.panelY() >= 0);
        assertTrue(layout.panelX() + layout.panelWidth() <= 1280);
        assertTrue(layout.panelY() + layout.panelHeight() <= 720);
    }

    @Test
    @DisplayName("a tiny window still produces a track with positive width")
    void tinyWindowStillProducesAUsableTrack() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(320, 240);

        assertTrue(layout.trackWidth() > 0);
        assertTrue(layout.trackX() >= layout.panelX());
        assertTrue(layout.trackX() + layout.trackWidth() <= layout.panelX() + layout.panelWidth());
    }

    @Test
    @DisplayName("indicator progress 0 and 1 land on the track's own ends")
    void indicatorEndsMatchTrackEnds() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(1280, 720);

        assertEquals(layout.trackX(), layout.indicatorX(0.0));
        assertEquals(layout.trackX() + layout.trackWidth(), layout.indicatorX(1.0));
    }

    @Test
    @DisplayName("indicator progress is clamped rather than running off the track")
    void indicatorProgressIsClamped() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(1280, 720);

        assertEquals(layout.indicatorX(1.0), layout.indicatorX(1.5));
        assertEquals(layout.indicatorX(0.0), layout.indicatorX(-0.5));
    }

    @Test
    @DisplayName("a zone twice as narrow as the travel duration covers half the track, centered")
    void zoneBoundsAreProportionalAndCentered() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(1280, 720);

        int[] zone = layout.zoneBounds(500, 1000);

        assertEquals(layout.trackWidth() / 2, zone[1], 1);
        int center = zone[0] + zone[1] / 2;
        assertEquals(layout.trackCenterX(), center, 1);
    }

    @Test
    @DisplayName("a zone as wide as the whole travel duration covers the entire track")
    void zoneAsWideAsTravelCoversWholeTrack() {
        CaptureMinigameLayout layout = CaptureMinigameLayout.fit(1280, 720);

        int[] zone = layout.zoneBounds(1000, 1000);

        assertEquals(layout.trackWidth(), zone[1], 1);
    }
}
