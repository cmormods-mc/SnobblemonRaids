package com.cobbleraids.client.capture;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Native, integer-aligned artwork for the Raid Capture Protocol's chamber, scanner, meter and
 * footer decoration: sharp rectangular borders, flat fills and scan-converted polygons, no scaled
 * background texture, filtering or blur pass. Every shape here is drawn fresh each frame rather than
 * blitted from a file, so it scales cleanly to any {@link CaptureUiLayout}.
 */
final class CaptureArt {
    private static final int DARK = 0xFF001A30;
    private static final int CYAN = 0xFF00E6F5;
    private static final int LIGHT = 0xFF70F8FF;

    private CaptureArt() {}

    /** The one primitive the art is built from; a live {@code GuiGraphics} or a baked image can back it. */
    @FunctionalInterface
    interface Pen {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    /** Rows the footer's decoration spans; its band starts {@link #footerTop} below the frame's top. */
    static final int FOOTER_ROWS = 30;

    static int footerTop(CaptureUiLayout layout) {
        return layout.height() - 13 - 21;
    }

    private static final BakedArt CHAMBER = new BakedArt("chamber");
    private static final BakedArt FOOTER = new BakedArt("footer");
    private static final BakedArt SCANNER = new BakedArt("scanner");

    /** Frees the baked textures; called when a capture screen closes so none outlive it. */
    static void release() {
        CHAMBER.release();
        FOOTER.release();
        SCANNER.release();
    }

    /** The chamber, painted once per size into a texture and blitted: it never changes between frames. */
    static void drawChamber(GuiGraphics g, int x, int y, int w, int h) {
        CHAMBER.draw(g, x, y, w, h, 0, pen -> chamber(pen, 0, 0, w, h));
    }

    /** Decorative circuitry along the frame's bottom edge; hidden at compact GUI scales. */
    static void drawFooter(GuiGraphics g, CaptureUiLayout layout) {
        if (layout.width() < 480) return;
        int top = footerTop(layout);
        FOOTER.draw(g, layout.x(), layout.y() + top, layout.width(), FOOTER_ROWS, layout.height(),
                pen -> footer(shifted(pen, top), layout.width(), layout.height()));
    }

    static Pen shifted(Pen pen, int dy) {
        return (x1, y1, x2, y2, c) -> pen.fill(x1, y1 - dy, x2, y2 - dy, c);
    }

    static void line(Pen g, int x, int y, int ex, int ey, int color) {
        int dx = Math.abs(ex - x), sx = x < ex ? 1 : -1;
        int dy = -Math.abs(ey - y), sy = y < ey ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fill(x, y, x + 1, y + 1, color);
            if (x == ex && y == ey) break;
            int e = 2 * error;
            if (e >= dy) { error += dy; x += sx; }
            if (e <= dx) { error += dx; y += sy; }
        }
    }

    static void ellipse(Pen g, int cx, int cy, int rx, int ry, int color) {
        if (rx < 1 || ry < 1) return;
        for (int yy = -ry; yy <= ry; yy++) {
            int xx = (int) Math.round(rx * Math.sqrt(Math.max(0, 1 - yy * (double) yy / (ry * ry))));
            g.fill(cx - xx, cy + yy, cx + xx + 1, cy + yy + 1, color);
        }
    }

    static void outlineEllipse(Pen g, int cx, int cy, int rx, int ry, int color) {
        for (int yy = -ry; yy <= ry; yy++) {
            int outer = (int) Math.round(rx * Math.sqrt(Math.max(0, 1 - yy * (double) yy / (ry * ry))));
            int inner = Math.abs(yy) >= ry - 2 ? 0
                    : (int) Math.round((rx - 2) * Math.sqrt(Math.max(0, 1 - yy * (double) yy / ((ry - 2) * (ry - 2)))));
            g.fill(cx - outer, cy + yy, cx - inner + 1, cy + yy + 1, color);
            g.fill(cx + inner, cy + yy, cx + outer + 1, cy + yy + 1, color);
        }
    }

    /** Fills a simple polygon by scanline, since {@code GuiGraphics} has no native polygon fill. */
    static void polygon(Pen g, int[] xs, int[] ys, int color) {
        int min = java.util.Arrays.stream(ys).min().orElse(0);
        int max = java.util.Arrays.stream(ys).max().orElse(0);
        for (int y = min; y < max; y++) {
            int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((ys[i] <= y && ys[j] > y) || (ys[j] <= y && ys[i] > y)) {
                    int xx = xs[i] + (int) ((long) (y - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i]));
                    left = Math.min(left, xx);
                    right = Math.max(right, xx);
                }
            }
            if (right > left) g.fill(left, y, right, y + 1, color);
        }
    }

    static void bracket(Pen g, int x, int y, int w, int h, int length, int color) {
        for (int side = 0; side < 2; side++) {
            for (int bottom = 0; bottom < 2; bottom++) {
                int px = side == 0 ? x : x + w - 2;
                int py = bottom == 0 ? y : y + h - 2;
                g.fill(side == 0 ? px : px - length + 2, py, side == 0 ? px + length : px + 2, py + 2, color);
                g.fill(px, bottom == 0 ? py : py - length + 2, px + 2, bottom == 0 ? py + length : py + 2, color);
            }
        }
    }

    /** The hologram chamber: perspective wall panels and ribs (scan-converted polygons), light rings. */
    static void chamber(Pen g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, DARK);
        int cx = x + w / 2, beam = w * 3 / 5, left = cx - beam / 2, right = left + beam;
        for (int side = 0; side < 2; side++) {
            int outer = side == 0 ? x + 7 : x + w - 8, inner = side == 0 ? left - 5 : right + 5;
            int ox = side == 0 ? outer + 7 : outer - 7, ix = side == 0 ? inner - 4 : inner + 4;
            polygon(g, new int[]{ox, ix, ix, ox}, new int[]{y + 20, y + 44, y + h - 44, y + h - 20}, 0xFF054887);
            polygon(g, new int[]{ox, ix, ix, ox},
                    new int[]{y + h / 3, y + h / 3 + 12, y + h * 2 / 3 - 12, y + h * 2 / 3}, 0xFF0756A5);
            for (int row = 0; row < 4; row++) {
                int yy = y + 9 + row * (h - 18) / 3;
                int iy = yy + (row < 2 ? 18 : -18);
                polygon(g, new int[]{outer, inner, inner, outer}, new int[]{yy, iy, iy + 5, yy + 5}, 0xFF0867BB);
            }
            for (int row = 0; row < 2; row++) {
                int yy = row == 0 ? y + h / 5 : y + h * 4 / 5, slope = row == 0 ? 7 : -7;
                int a = side == 0 ? ox + 3 : ox - 3, b = side == 0 ? ix - 4 : ix + 4;
                polygon(g, new int[]{a, b, b, a}, new int[]{yy - 5, yy + slope - 5, yy + slope + 5, yy + 5}, 0xFF0086E8);
                polygon(g, new int[]{a, b, b, a}, new int[]{yy - 2, yy + slope - 2, yy + slope + 2, yy + 2}, CYAN);
            }
        }
        int[] bands = {0xFF075AAA, 0xFF008CE5, 0xFF00B9EF, 0xFF05CDEB, 0xFF1EDCEE, 0xFF05CDEB, 0xFF00B9EF, 0xFF008CE5, 0xFF075AAA};
        for (int i = 0; i < bands.length; i++) {
            g.fill(left + beam * i / 9, y, left + beam * (i + 1) / 9, y + h, bands[i]);
        }
        int ringY = Math.max(12, h / 10), ry = Math.max(5, h / 17), rx = beam / 2 + 5;
        for (int yy : new int[]{y + ringY, y + h - ringY}) {
            ellipse(g, cx, yy + 3, rx, ry, 0xFF008BDF);
            ellipse(g, cx, yy, rx, ry, LIGHT);
            ellipse(g, cx, yy, rx - 7, Math.max(2, ry - 3), 0xFF007FC5);
            ellipse(g, cx, yy, rx - 19, Math.max(1, ry - 4), 0xFF00C5EC);
            g.fill(cx - rx / 3, yy - ry + 3, cx + rx / 3, yy + ry - 3, 0xFF03C9E9);
        }
        int[][] particles = {{1, 3}, {8, 3}, {2, 6}, {7, 7}, {1, 8}, {8, 6}};
        for (int[] p : particles) {
            int px = left + beam * p[0] / 10, py = y + h * p[1] / 10;
            g.fill(px, py, px + 4, py + 5, LIGHT);
        }
        bracket(g, x + 6, y + 6, w - 12, h - 12, Math.max(7, Math.min(18, w / 10)), CYAN);
        bracket(g, cx - beam / 3, y + h / 4, beam * 2 / 3, h / 2, 10, 0xFFE9FFFF);
    }

    /** The stabilization-quality meter beside the chamber; hidden below a usable minimum size. */
    static void meter(GuiGraphics g, CaptureUiLayout layout, double quality) {
        if (layout.chamberWidth() < 150 || layout.contentHeight() < 120) return;
        int x = layout.chamberX() + layout.chamberWidth() - 20;
        int y = layout.contentY() + layout.contentHeight() / 5;
        int h = layout.contentHeight() * 3 / 5;
        CaptureUi.panel(g, x, y, 10, h, DARK, CYAN);
        int count = 12, filled = (int) Math.round(Math.clamp(quality, 0, 1) * count);
        for (int i = 0; i < count; i++) {
            int top = y + 4 + i * (h - 8) / count, bottom = y + 4 + (i + 1) * (h - 8) / count - 1;
            if (bottom > top) g.fill(x + 3, top, x + 7, bottom, i >= count - filled ? CYAN : 0xFF075385);
        }
    }

    /** The targeting-bracket scanner grid and its three progress boxes; hidden when too small to read. */
    static void scanner(GuiGraphics g, Font font, int x, int y, int w, int h, int completed) {
        if (h < 40 || w < 90) return;
        SCANNER.draw(g, x, y, w, h, 0, pen -> scannerStatic(pen, w, h));
        int gx = x + w * 4 / 5, gy = y + h / 3, gr = Math.min(14, h / 5);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 - 90);
            int px = gx + (int) Math.round(Math.cos(a) * gr), py = gy + (int) Math.round(Math.sin(a) * gr);
            g.fill(px - 2, py - 2, px + 2, py + 2, i < completed * 4 ? CYAN : 0xFF7294AC);
        }
        int box = Math.min(19, (w / 2 - 7) / 3), start = x + w - 3 * box - 4;
        for (int i = 0; i < 3; i++) {
            int bx = start + i * box, by = y + h - 21;
            CaptureUi.panel(g, bx, by, box - 2, 18, DARK, i < completed ? CYAN : 0xFF537C99);
            CaptureUi.centered(g, font, Integer.toString(i + 1), bx + 2, by + 5, box - 6,
                    i < completed ? CYAN : 0xFFABC9DD);
        }
    }

    /** The scanner's unchanging half, in local coordinates: grid, targeting ring and crosshair. */
    static void scannerStatic(Pen g, int w, int h) {
        int x = 0, y = 0;
        g.fill(x, y, x + w, y + h, DARK);
        for (int xx = x; xx < x + w; xx += 12) g.fill(xx, y, xx + 1, y + h, 0xFF0A5074);
        for (int yy = y; yy < y + h; yy += 12) g.fill(x, yy, x + w, yy + 1, 0xFF0A5074);
        int radius = Math.min(h / 2 - 6, w / 4), cx = x + w / 3, cy = y + h / 2;
        outlineEllipse(g, cx, cy, radius, radius, CYAN);
        g.fill(cx - radius + 2, cy - 3, cx + radius - 1, cy + 3, DARK);
        g.fill(cx - radius + 2, cy - 3, cx + radius - 1, cy - 1, CYAN);
        g.fill(cx - radius + 2, cy + 1, cx + radius - 1, cy + 3, CYAN);
        ellipse(g, cx, cy, 7, 7, CYAN);
        ellipse(g, cx, cy, 5, 5, DARK);
        ellipse(g, cx, cy, 3, 3, CYAN);
        bracket(g, cx - radius - 4, cy - radius - 4, radius * 2 + 8, radius * 2 + 8, 6, CYAN);
    }

    /** A small crisp RP crystal icon, kept clear of the dynamic RP amount it sits beside. */
    static void crystal(GuiGraphics g, int x, int y) {
        for (int yy = 0; yy < 14; yy++) {
            int half = yy < 7 ? yy / 2 : (13 - yy) / 2;
            g.fill(x + 4 - half, y + yy, x + 5 + half, y + yy + 1, 0xFFD947DD);
            g.fill(x + 4, y + yy, x + 5, y + yy + 1, 0xFFFFE9FF);
        }
    }

    /** The footer circuitry in the frame's local coordinates (origin at the frame's top-left). */
    static void footer(Pen g, int width, int height) {
        int y = height - 13;
        for (int side = 0; side < 2; side++) {
            int a = side == 0 ? 18 : width - 18;
            int dir = side == 0 ? 1 : -1;
            for (int i = 0; i < 3; i++) {
                g.fill(a + dir * (35 + i * 10) - 2, y - 16, a + dir * (35 + i * 10) + 2, y - 1, DARK);
            }
            line(g, a, y + 7, a, y - 8, CYAN);
            line(g, a, y - 8, a - dir * 12, y - 20, CYAN);
            int start = a + dir * 72, end = a + dir * (width / 2 - 115);
            if (Math.abs(end - start) > 10) {
                line(g, start, y - 11, start + dir * 10, y - 11, DARK);
                line(g, start + dir * 10, y - 11, start + dir * 17, y - 4, DARK);
                line(g, start + dir * 17, y - 4, end, y - 4, DARK);
                g.fill(end - 2, y - 6, end + 2, y - 2, CYAN);
            }
        }
    }

    /** The progress-lamp strip in the header's top-right corner; hidden at compact GUI scales. */
    static void lamps(GuiGraphics g, Font font, CaptureUiLayout layout, String label, int count) {
        if (layout.width() < 460) return;
        int right = layout.x() + layout.width() - 17;
        int labelWidth = font.width(label);
        CaptureUi.text(g, font, label, right - labelWidth, layout.headerY(), labelWidth, CYAN);
        for (int i = 0; i < 3; i++) {
            int x = right - labelWidth - 42 + i * 11;
            g.fill(x, layout.headerY(), x + 7, layout.headerY() + 7, CYAN);
            g.fill(x + 1, layout.headerY() + 1, x + 6, layout.headerY() + 6, i < count ? LIGHT : DARK);
        }
    }
}
