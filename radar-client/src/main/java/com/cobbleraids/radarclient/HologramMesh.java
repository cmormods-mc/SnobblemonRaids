package com.cobbleraids.radarclient;

import com.cobbleraids.radar.protocol.RadarHologramPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cached, bounded real geometry: 32x32 terrain cells, at most twelve elevation terraces. */
public final class HologramMesh {
    public static final int CELLS = 32, BANDS = 12;
    public static final float FLOOR = 0.055f, RISE = 0.018f;
    public static final float PROJECTION_LIFT = 0.28f;
    public static float projectedHeight(float height, float emergence) {
        return (PROJECTION_LIFT + height * 1.25f) * emergence;
    }
    public record Vertex(float x, float y, float z) {}
    public record Quad(Vertex a, Vertex b, Vertex c, Vertex d, Vertex normal, int color) {}
    public record Mesh(List<Quad> quads, int[] bands, float maximumY) {
        public float surfaceAt(float x, float z) {
            int cellX = Math.clamp((int) ((x + 0.5f) * CELLS), 0, CELLS - 1);
            int cellZ = Math.clamp((int) ((z + 0.5f) * CELLS), 0, CELLS - 1);
            return FLOOR + Math.max(0, bands[cellZ * CELLS + cellX]) * RISE;
        }
    }
    private static final Vertex UP = new Vertex(0, 1, 0);
    private HologramMesh() {}

    public static Mesh build(int[] heights) {
        if (heights.length != RadarHologramPayload.SAMPLE_COUNT) throw new IllegalArgumentException("Expected 33x33 samples");
        int[] cells = new int[CELLS * CELLS], bands = new int[cells.length];
        Arrays.fill(cells, RadarHologramPayload.UNKNOWN);
        Arrays.fill(bands, -1);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int z = 0; z < CELLS; z++) for (int x = 0; x < CELLS; x++) {
            float x0 = x / (float) CELLS - 0.5f, z0 = z / (float) CELLS - 0.5f;
            // Keep the whole cell inside the compass bezel, including its corners.
            float farX = Math.max(Math.abs(x0), Math.abs(x0 + 1 / (float) CELLS));
            float farZ = Math.max(Math.abs(z0), Math.abs(z0 + 1 / (float) CELLS));
            if (farX * farX + farZ * farZ > 0.46f * 0.46f) continue;
            int step = 32 / CELLS;
            int i = z * step * 33 + x * step;
            int a = heights[i], b = heights[i + step], c = heights[i + step * 33], d = heights[i + step * 34];
            if (a == RadarHologramPayload.UNKNOWN || b == RadarHologramPayload.UNKNOWN
                    || c == RadarHologramPayload.UNKNOWN || d == RadarHologramPayload.UNKNOWN) continue;
            int average = (int) (((long) a + b + c + d) / 4);
            cells[z * CELLS + x] = average;
            min = Math.min(min, average); max = Math.max(max, average);
        }
        int interval = min == Integer.MAX_VALUE ? 8 : Math.max(8, (max - min + BANDS - 1) / BANDS);
        for (int i = 0; i < cells.length; i++) if (cells[i] != RadarHologramPayload.UNKNOWN) {
            bands[i] = Math.min(BANDS, 1 + (cells[i] - min) / interval);
        }
        List<Quad> quads = new ArrayList<>();
        float maximum = FLOOR;
        for (int z = 0; z < CELLS; z++) for (int x = 0; x < CELLS; x++) {
            int band = bands[z * CELLS + x];
            if (band < 0) continue;
            float x0 = x / (float) CELLS - 0.5f, x1 = x0 + 1 / (float) CELLS;
            float z0 = z / (float) CELLS - 0.5f, z1 = z0 + 1 / (float) CELLS;
            float y = FLOOR + band * RISE;
            maximum = Math.max(maximum, y);
            int top = 0xB0083944;
            quads.add(quad(x0,y,z0, x0,y,z1, x1,y,z1, x1,y,z0, UP, top));
            wire(quads, x0, z0, x1, z0, y + .002f);
            wire(quads, x0, z0, x0, z1, y + .002f);
            wire(quads, x0, z0, x1, z1, y + .002f);
            // Only exposed faces are emitted. Narrow top strips make each terrace readable.
            boundary(quads, band, neighbor(bands,x-1,z), x0,z1,x0,z0, new Vertex(-1,0,0));
            boundary(quads, band, neighbor(bands,x+1,z), x1,z0,x1,z1, new Vertex(1,0,0));
            boundary(quads, band, neighbor(bands,x,z-1), x0,z0,x1,z0, new Vertex(0,0,-1));
            boundary(quads, band, neighbor(bands,x,z+1), x1,z1,x0,z1, new Vertex(0,0,1));
        }
        return new Mesh(List.copyOf(quads), bands, maximum);
    }
    private static int neighbor(int[] bands, int x, int z) {
        return x < 0 || z < 0 || x >= CELLS || z >= CELLS ? -1 : bands[z * CELLS + x];
    }
    private static void boundary(List<Quad> quads, int band, int neighbor,
                                 float ax, float az, float bx, float bz, Vertex normal) {
        if (neighbor >= band) return;
        float top = FLOOR + band * RISE, bottom = FLOOR + Math.max(0, neighbor) * RISE;
        quads.add(quad(ax,bottom,az, ax,top,az, bx,top,bz, bx,bottom,bz, normal, 0x900F6673));
        float width = 0.0028f, ox = -normal.x * width, oz = -normal.z * width;
        quads.add(quad(ax+ox,top+.001f,az+oz, bx+ox,top+.001f,bz+oz,
                bx,top+.001f,bz, ax,top+.001f,az, UP, 0xEE198F98));
    }
    private static void wire(List<Quad> quads, float ax, float az, float bx, float bz, float y) {
        float length = (float) Math.hypot(bx - ax, bz - az);
        float ox = -(bz - az) / length * .0012f, oz = (bx - ax) / length * .0012f;
        quads.add(quad(ax+ox,y,az+oz, bx+ox,y,bz+oz, bx-ox,y,bz-oz, ax-ox,y,az-oz, UP, 0xD00D8B8B));
    }
    private static Quad quad(float ax,float ay,float az,float bx,float by,float bz,
                             float cx,float cy,float cz,float dx,float dy,float dz,Vertex normal,int color) {
        return new Quad(new Vertex(ax,ay,az),new Vertex(bx,by,bz),new Vertex(cx,cy,cz),new Vertex(dx,dy,dz),normal,color);
    }
}
