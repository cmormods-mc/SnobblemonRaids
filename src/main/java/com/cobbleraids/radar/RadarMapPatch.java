package com.cobbleraids.radar;

/** Minimum enclosing rectangle of changed map pixels. Null means no packet is needed. */
public record RadarMapPatch(int x, int y, int width, int height, byte[] pixels) {
    public static RadarMapPatch between(byte[] previous, byte[] current) {
        int size = RadarRenderer.SIZE;
        if (current.length != size * size || (previous != null && previous.length != current.length)) {
            throw new IllegalArgumentException("Expected 128x128 frames");
        }
        int minX = size, minY = size, maxX = -1, maxY = -1;
        for (int i = 0; i < current.length; i++) if (previous == null || previous[i] != current[i]) {
            minX = Math.min(minX, i % size); maxX = Math.max(maxX, i % size);
            minY = Math.min(minY, i / size); maxY = Math.max(maxY, i / size);
        }
        if (maxX < 0) return null;
        int width = maxX - minX + 1, height = maxY - minY + 1;
        byte[] pixels = new byte[width * height];
        for (int row = 0; row < height; row++) System.arraycopy(current, (minY + row) * size + minX, pixels, row * width, width);
        return new RadarMapPatch(minX, minY, width, height, pixels);
    }
}
