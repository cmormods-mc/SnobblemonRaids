package com.cobbleraids.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RadarRendererTest {
    @Test void northEastAndHeightUseConsistentCoordinates() {
        var origin = RadarRenderer.project(0, 0, 0);
        assertTrue(RadarRenderer.project(0, -100, 0).y() < origin.y());
        assertTrue(RadarRenderer.project(100, 0, 0).x() > origin.x());
        assertTrue(RadarRenderer.project(0, 0, 30).y() < origin.y());
        assertEquals(origin.x(), RadarRenderer.project(0, 0, 30).x());
    }
    @Test void offscreenTargetRetainsBearingAtTheEdge() {
        var point = RadarRenderer.project(2000, -1000, 0);
        var edge = RadarRenderer.clampTarget(point, RadarRenderer.project(0, 0, 0));
        assertEquals(42, Math.hypot(edge.x() - 64, edge.y() - 63), 1e-9);
        assertEquals((point.y() - 63) / (point.x() - 64), (edge.y() - 63) / (edge.x() - 64), 1e-9);
        var nearby = RadarRenderer.project(10, 10, 0);
        assertEquals(nearby, RadarRenderer.clampTarget(nearby, RadarRenderer.project(0, 0, 0)));
    }
    @Test void edgeBearingStaysCorrectWhenPlayerIsNotAtTheMapCentre() {
        var player = RadarRenderer.project(70, 50, 0);
        var target = RadarRenderer.project(2000, -1000, 0);
        var edge = RadarRenderer.clampTarget(target, player);
        assertEquals(42, Math.hypot(edge.x() - 64, edge.y() - 63), 1e-9);
        assertEquals((target.y() - player.y()) / (target.x() - player.x()),
                (edge.y() - player.y()) / (edge.x() - player.x()), 1e-9);
    }
    @Test void facingArrowAgreesWithTheProjectedGeographicBearing() {
        var origin = RadarRenderer.project(0, 0, 0);
        var target = RadarRenderer.project(100, -100, 0);
        var facing = RadarRenderer.facing(-135);
        assertEquals((target.y() - origin.y()) / (target.x() - origin.x()), facing.y() / facing.x(), 1e-9);
    }
    @Test void unknownTerrainNeverInventsContours() {
        int[] heights = new int[RadarRenderer.GRID * RadarRenderer.GRID];
        Arrays.fill(heights, RadarRenderer.UNKNOWN);
        byte[] frame = RadarRenderer.terrain(heights, 64);
        for (byte pixel : frame) {
            assertNotEquals(RadarRenderer.CONTOUR, pixel);
            assertNotEquals(RadarRenderer.SHADOW, pixel);
        }
    }
    @Test void mountainsAddContoursAndOverlaysDoNotMutateCachedTerrain() {
        int[] heights = new int[RadarRenderer.GRID * RadarRenderer.GRID];
        for (int z = 0; z < RadarRenderer.GRID; z++) for (int x = 0; x < RadarRenderer.GRID; x++) {
            heights[z * RadarRenderer.GRID + x] = 64 + (int) Math.max(0, 100 - Math.hypot(x - 16, z - 16) * 9);
        }
        byte[] background = RadarRenderer.terrain(heights, 64), original = background.clone();
        boolean contour = false;
        for (byte color : background) contour |= color == RadarRenderer.CONTOUR;
        assertTrue(contour);
        byte[] frame = RadarRenderer.frame(background, RadarRenderer.project(0, 0, 0), 90,
                RadarRenderer.project(1200, -600, 0), "1342M", "LEGENDARY", false);
        assertArrayEquals(original, background);
        assertFalse(Arrays.equals(frame, background));
        assertEquals(128 * 128, frame.length);
        boolean raidMarker = false;
        for (byte color : frame) raidMarker |= color == RadarRenderer.ORANGE;
        assertTrue(raidMarker);
    }
    @Test void malformedTerrainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RadarRenderer.terrain(new int[10], 64));
    }
}
