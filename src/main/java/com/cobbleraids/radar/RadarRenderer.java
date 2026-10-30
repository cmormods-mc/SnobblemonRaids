package com.cobbleraids.radar;

import java.util.Locale;

/** A flat 128px map with illustrated elevation. No Minecraft, client renderer or shader dependency. */
public final class RadarRenderer {
    public static final int SIZE = 128;
    public static final int GRID = 33;
    public static final int STEP = 16;
    public static final int UNKNOWN = Integer.MIN_VALUE;
    // Logical colors; the server translates these to Minecraft's legal map palette once per frame.
    public static final byte BLACK = 0, GRID_COLOR = 1, LOW = 2, CONTOUR = 3,
            CYAN = 4, WHITE = 5, ORANGE = 6, SHADOW = 7;
    private static final int CX = 64, CZ = 63;
    private static final int[] CORNER_X = {0, 1, 1, 0}, CORNER_Z = {0, 0, 1, 1};
    private RadarRenderer() {}

    public record Point(double x, double y) {}

    /** North-up; altitude separates contour slices, with less depth than horizontal width. */
    public static Point project(double dx, double dz, double dy) {
        return new Point(CX + dx * 0.16, CZ + dz * 0.115 - Math.clamp(dy * 0.12, -12, 12));
    }

    /** Clamp a distant target to the inner edge, preserving its on-screen bearing. */
    public static Point clampTarget(Point point, Point player) {
        if (Math.hypot(point.x - CX, point.y - CZ) <= 42) return point;
        // Intersect the player's actual bearing with the circle, rather than aiming from its centre.
        double dx = point.x - player.x, dy = point.y - player.y;
        double px = player.x - CX, py = player.y - CZ;
        double a = dx * dx + dy * dy, b = 2 * (px * dx + py * dy);
        double c = px * px + py * py - 42 * 42;
        double t = (-b + Math.sqrt(b * b - 4 * a * c)) / (2 * a);
        return new Point(player.x + t * dx, player.y + t * dy);
    }

    /** Apply the same compressed north/south axis to the arrow as to the terrain and raid. */
    public static Point facing(float yaw) {
        double angle = Math.toRadians(yaw);
        double x = -Math.sin(angle), y = Math.cos(angle) * 0.115 / 0.16;
        double length = Math.hypot(x, y);
        return new Point(x / length, y / length);
    }

    public static byte[] terrain(int[] heights, int baseY) {
        if (heights.length != GRID * GRID) throw new IllegalArgumentException("Expected 33x33 terrain");
        byte[] pixels = new byte[SIZE * SIZE];
        for (int y = 17; y < 110; y++) for (int x = 18; x < 111; x++) {
            if (inside(x, y) && (x % 10 == 4 || y % 10 == 3)) pixel(pixels, x, y, GRID_COLOR);
        }
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int height : heights) if (height != UNKNOWN) { min = Math.min(min, height); max = Math.max(max, height); }
        // Bound the number of contour passes even for amplified terrain and extreme heights.
        int interval = min == Integer.MAX_VALUE ? 16 : Math.max(16, (max - min + 15) / 16);
        int[] corners = new int[4];
        double[] crossingX = new double[4], crossingY = new double[4];
        if (min != Integer.MAX_VALUE) {
            for (int elevation = Math.floorDiv(min, interval) * interval; elevation <= max; elevation += interval) {
                for (int z = 0; z < GRID - 1; z++) for (int x = 0; x < GRID - 1; x++) {
                    contour(pixels, heights, x, z, elevation, baseY, ((elevation / interval) & 1) == 0 ? CONTOUR : LOW, corners, crossingX, crossingY);
                }
            }
        }
        ring(pixels, 49, CYAN);
        ring(pixels, 47, LOW);
        for (int degrees = 0; degrees < 360; degrees += 15) {
            double angle = Math.toRadians(degrees);
            int outer = degrees % 90 == 0 ? 54 : 51;
            line(pixels, CX + 49 * Math.sin(angle), CZ + 49 * Math.cos(angle),
                    CX + outer * Math.sin(angle), CZ + outer * Math.cos(angle), CYAN, false);
        }
        text(pixels, 54, 0, "RADAR", CYAN);
        text(pixels, 63, 7, "N", WHITE);
        text(pixels, 119, 61, "E", WHITE);
        text(pixels, 63, 116, "S", WHITE);
        text(pixels, 4, 61, "W", WHITE);
        return pixels;
    }

    public static byte[] frame(byte[] background, Point player, float yaw, Point raid,
                               String distance, String tier, boolean scanning) {
        byte[] pixels = background.clone();
        if (raid != null) {
            Point end = clampTarget(raid, player);
            double dx = end.x - player.x, dy = end.y - player.y;
            int length = (int) Math.ceil(Math.hypot(dx, dy));
            for (int i = 0; i <= length; i += 4) {
                double t = length == 0 ? 0 : (double) i / length;
                dot(pixels, (int) Math.round(player.x + dx * t), (int) Math.round(player.y + dy * t), CYAN);
            }
            int x = (int) Math.round(end.x), y = (int) Math.round(end.y);
            line(pixels, x, y - 4, x + 4, y, ORANGE, false);
            line(pixels, x + 4, y, x, y + 4, ORANGE, false);
            line(pixels, x, y + 4, x - 4, y, ORANGE, false);
            line(pixels, x - 4, y, x, y - 4, ORANGE, false);
            pixel(pixels, x, y, WHITE);
        }
        Point facing = facing(yaw);
        double fx = facing.x, fy = facing.y;
        double px = -fy, py = fx;
        line(pixels, player.x + fx * 4, player.y + fy * 4,
                player.x - fx * 3 + px * 3, player.y - fy * 3 + py * 3, WHITE, false);
        line(pixels, player.x + fx * 4, player.y + fy * 4,
                player.x - fx * 3 - px * 3, player.y - fy * 3 - py * 3, WHITE, false);
        dot(pixels, (int) player.x, (int) player.y, CYAN);
        text(pixels, 5, 121, raid == null ? "NO SIGNAL" : distance, raid == null ? LOW : ORANGE);
        text(pixels, Math.max(66, 123 - tier.length() * 4), 121, tier, CYAN);
        if (scanning) text(pixels, 47, 106, "SCANNING", LOW);
        return pixels;
    }

    private static void contour(byte[] pixels, int[] heights, int x, int z, int elevation, int baseY,
                                byte color, int[] h, double[] crossingX, double[] crossingY) {
        for (int i = 0; i < 4; i++) {
            h[i] = heights[(z + CORNER_Z[i]) * GRID + x + CORNER_X[i]];
            if (h[i] == UNKNOWN) return;
        }
        int count = 0;
        for (int edge = 0; edge < 4; edge++) {
            int next = (edge + 1) % 4;
            if ((h[edge] >= elevation) == (h[next] >= elevation)) continue;
            double t = (double) (elevation - h[edge]) / (h[next] - h[edge]);
            crossingX[count] = CX + (x + CORNER_X[edge] + (CORNER_X[next] - CORNER_X[edge]) * t - 16) * STEP * 0.16;
            crossingY[count++] = CZ + (z + CORNER_Z[edge] + (CORNER_Z[next] - CORNER_Z[edge]) * t - 16) * STEP * 0.115
                    - Math.clamp((elevation - baseY) * 0.12, -12, 12);
        }
        for (int i = 0; i + 1 < count; i += 2) {
            line(pixels, crossingX[i], crossingY[i] + 2, crossingX[i + 1], crossingY[i + 1] + 2, SHADOW, true);
            line(pixels, crossingX[i], crossingY[i], crossingX[i + 1], crossingY[i + 1], color, true);
        }
    }

    private static boolean inside(int x, int y) { return (x - CX) * (x - CX) + (y - CZ) * (y - CZ) < 45 * 45; }
    private static void ring(byte[] pixels, int radius, byte color) {
        for (int i = 0; i < 720; i++) {
            double angle = i * Math.PI / 360;
            pixel(pixels, CX + (int) Math.round(Math.cos(angle) * radius), CZ + (int) Math.round(Math.sin(angle) * radius), color);
        }
    }
    private static void dot(byte[] pixels, int x, int y, byte color) {
        pixel(pixels, x, y, color); pixel(pixels, x + 1, y, color); pixel(pixels, x, y + 1, color);
    }
    private static void line(byte[] pixels, double ax, double ay, double bx, double by, byte color, boolean clip) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay))));
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(ax + (bx - ax) * i / steps), y = (int) Math.round(ay + (by - ay) * i / steps);
            if (!clip || inside(x, y)) pixel(pixels, x, y, color);
        }
    }
    private static void pixel(byte[] pixels, int x, int y, byte color) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) pixels[y * SIZE + x] = color;
    }
    private static void text(byte[] pixels, int x, int y, String value, byte color) {
        for (char ch : value.toUpperCase(Locale.ROOT).toCharArray()) {
            int glyph = glyph(ch);
            for (int row = 0; row < 5; row++) for (int col = 0; col < 3; col++) {
                if ((glyph & (1 << (14 - row * 3 - col))) != 0) pixel(pixels, x + col, y + row, color);
            }
            x += 4;
        }
    }
    private static int glyph(char c) {
        return switch (c) {
            case '0','O' -> 0b111_101_101_101_111; case '1' -> 0b010_110_010_010_111;
            case '2' -> 0b111_001_111_100_111; case '3' -> 0b111_001_111_001_111;
            case '4' -> 0b101_101_111_001_001; case '5','S' -> 0b111_100_111_001_111;
            case '6' -> 0b111_100_111_101_111; case '7' -> 0b111_001_010_010_010;
            case '8' -> 0b111_101_111_101_111; case '9' -> 0b111_101_111_001_111;
            case 'A' -> 0b010_101_111_101_101; case 'C' -> 0b111_100_100_100_111;
            case 'D' -> 0b110_101_101_101_110; case 'E' -> 0b111_100_110_100_111;
            case 'G' -> 0b111_100_101_101_111; case 'H' -> 0b101_101_111_101_101;
            case 'I' -> 0b111_010_010_010_111; case 'L' -> 0b100_100_100_100_111;
            case 'M' -> 0b101_111_111_101_101; case 'N' -> 0b101_111_111_111_101;
            case 'P' -> 0b110_101_110_100_100; case 'R' -> 0b110_101_110_101_101;
            case 'T' -> 0b111_010_010_010_010; case 'U' -> 0b101_101_101_101_111;
            case 'W' -> 0b101_101_111_111_101; case 'Y' -> 0b101_101_010_010_010;
            case '.' -> 0b000_000_000_000_010; case '-' -> 0b000_000_111_000_000;
            default -> 0;
        };
    }
}
