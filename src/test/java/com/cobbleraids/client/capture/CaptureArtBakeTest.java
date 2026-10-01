package com.cobbleraids.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Baking paints into a fixed-size image and clips to it, so anything the art draws outside that
 * image would silently vanish, where the live fills it replaced would have drawn it. These pin the
 * bounds the textures are sized to.
 */
class CaptureArtBakeTest {
    private record Bounds(int[] box, int[] count) {
        CaptureArt.Pen pen() {
            return (x1, y1, x2, y2, c) -> {
                box[0] = Math.min(box[0], Math.min(x1, x2));
                box[1] = Math.min(box[1], Math.min(y1, y2));
                box[2] = Math.max(box[2], Math.max(x1, x2));
                box[3] = Math.max(box[3], Math.max(y1, y2));
                count[0]++;
            };
        }

        static Bounds fresh() {
            return new Bounds(new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}, new int[1]);
        }
    }

    @Test
    @DisplayName("the footer's art fits inside its baked band at every width the footer is shown")
    void footerFitsItsBand() {
        for (int width = 480; width <= 700; width += 10) {
            for (int height = 210; height <= 420; height += 30) {
                CaptureUiLayout layout = new CaptureUiLayout(0, 0, width, height);
                Bounds b = Bounds.fresh();
                CaptureArt.footer(CaptureArt.shifted(b.pen(), CaptureArt.footerTop(layout)), width, height);
                String at = width + "x" + height;
                assertTrue(b.count()[0] > 0, "footer painted nothing at " + at);
                assertTrue(b.box()[0] >= 0 && b.box()[2] <= width, "footer overflows horizontally at " + at);
                assertTrue(b.box()[1] >= 0 && b.box()[3] <= CaptureArt.FOOTER_ROWS,
                        "footer overflows its band at " + at + ": " + b.box()[1] + ".." + b.box()[3]);
            }
        }
    }

    @Test
    @DisplayName("the chamber paints within its own rectangle")
    void chamberStaysInBounds() {
        for (int w = 100; w <= 440; w += 40) {
            for (int h = 60; h <= 280; h += 40) {
                Bounds b = Bounds.fresh();
                CaptureArt.chamber(b.pen(), 0, 0, w, h);
                String at = w + "x" + h;
                assertTrue(b.box()[0] >= 0 && b.box()[1] >= 0, "chamber paints above/left of its image at " + at);
                assertTrue(b.box()[2] <= w && b.box()[3] <= h, "chamber paints past its image at " + at);
            }
        }
    }

    @Test
    @DisplayName("the scanner's static half paints within its rectangle")
    void scannerStaysInBounds() {
        for (int w = 90; w <= 200; w += 10) {
            for (int h = 40; h <= 120; h += 10) {
                Bounds b = Bounds.fresh();
                CaptureArt.scannerStatic(b.pen(), w, h);
                String at = w + "x" + h;
                assertTrue(b.box()[0] >= 0 && b.box()[1] >= 0, "scanner paints above/left of its image at " + at);
                assertTrue(b.box()[2] <= w && b.box()[3] <= h, "scanner paints past its image at " + at);
            }
        }
    }

    @Test
    @DisplayName("ARGB art colors become the ABGR NativeImage expects, alpha and green untouched")
    void colorSwap() {
        assertEquals(0xFF0000FF, BakedArt.toAbgr(0xFFFF0000));
        assertEquals(0xFFFF0000, BakedArt.toAbgr(0xFF0000FF));
        assertEquals(0xFF00FF00, BakedArt.toAbgr(0xFF00FF00));
        assertEquals(0xFF70F8FF, BakedArt.toAbgr(0xFFFFF870));
    }
}
