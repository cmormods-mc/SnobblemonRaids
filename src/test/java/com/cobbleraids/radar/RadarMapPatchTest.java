package com.cobbleraids.radar;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RadarMapPatchTest {
    @Test void firstFrameIsCompleteAndUnchangedFrameSendsNothing() {
        byte[] frame = new byte[128 * 128];
        var patch = RadarMapPatch.between(null, frame);
        assertEquals(128, patch.width()); assertEquals(128, patch.height());
        assertArrayEquals(frame, patch.pixels());
        assertNull(RadarMapPatch.between(frame, frame.clone()));
    }
    @Test void singlePixelMovementDoesNotSendAnEntireMap() {
        byte[] old = new byte[128 * 128], next = old.clone();
        next[57 * 128 + 19] = 6;
        var patch = RadarMapPatch.between(old, next);
        assertEquals(19, patch.x()); assertEquals(57, patch.y());
        assertEquals(1, patch.width()); assertEquals(1, patch.height());
        assertArrayEquals(new byte[] {6}, patch.pixels());
    }
    @Test void applyingPatchesExactlyReconstructsFramesIncludingEdgePixels() {
        Random random = new Random(42);
        byte[] client = new byte[128 * 128];
        for (int frame = 0; frame < 100; frame++) {
            byte[] next = client.clone();
            for (int i = 0; i <= frame; i++) next[random.nextInt(next.length)] = (byte) random.nextInt(256);
            next[frame % 2 == 0 ? 0 : next.length - 1]++;
            var patch = RadarMapPatch.between(client, next);
            assertNotNull(patch);
            for (int row = 0; row < patch.height(); row++) System.arraycopy(patch.pixels(), row * patch.width(),
                    client, (patch.y() + row) * 128 + patch.x(), patch.width());
            assertArrayEquals(next, client);
        }
    }
}
