package com.cobbleraids.worldboss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shape of the World Boss dome: a flattened geodesic dome made of flat triangles. Pure maths with no Minecraft types, so every number
 * can be tested without a server.
 *
 * <p>Vanilla Minecraft has no smooth sphere model, so the dome is a mesh of flat glass panels, each one display entity showing a two-sided
 * quad (AscensionLib's {@code world_event_dome_N} models) whose texture holds an up-pointing isosceles triangle: base along the bottom, apex at
 * the top middle. A display entity's transform is {@code translation * left * scale * right}, applied to the quad's corners, which is enough to
 * land that texture triangle on ANY triangle: its 2x2 in-plane matrix {@code [[width, shear], [0, height]]} (the shear is how far the real
 * apex sits off the middle of the base) is split by a singular value decomposition into a rotation, a stretch and a rotation, which are the
 * entity's left rotation (after the panel's tilt), scale and right rotation.
 *
 * <p>The mesh is an icosahedron stood on a vertex and cut {@code detail} times (frequency 2, 4, 8). Standing on a vertex puts an equator of
 * edges exactly at the middle, so keeping the triangles at or above it gives a flat, clean base: 40, 160 or 640 triangles for a hemisphere,
 * twice that for the whole sphere. The unit sphere is squashed to {@code height} tall each way so the dome is as wide as the zone but low
 * enough to stay under the build limit.
 */
final class DomeGeometry {

    /** How many swirl variants the pack has; the pack's next model number is the flash (see {@code WorldBossDome}). */
    static final int SWIRL_VARIANTS = 3;
    /** Panels are drawn a little larger than their triangle (about its centre) so neighbours overlap instead of leaving hairline gaps. */
    static final double OVERLAP = 1.06;
    private static final double EPSILON = 1e-9;

    private DomeGeometry() {}

    /**
     * One panel, with its position relative to the dome's centre.
     *
     * @param x,y,z              centre of the quad
     * @param lx,ly,lz,lw        left rotation: the in-plane turn after the stretch, then the panel's tilt (unit quaternion)
     * @param rx,ry,rz,rw        right rotation: the in-plane turn before the stretch (unit quaternion)
     * @param s1,s2              stretch along the quad's X and Y after the right rotation
     * @param variant            which swirl variant it uses, 0 to {@link #SWIRL_VARIANTS} - 1
     * @param nx,ny,nz           unit normal of the panel, used to offset the crossing flash in front of it
     * @param triangle           the nine coordinates of the (grown) triangle it covers: base-left, base-right, apex
     */
    record Panel(double x, double y, double z, float lx, float ly, float lz, float lw, float rx, float ry, float rz, float rw,
                 float s1, float s2, int variant, double nx, double ny, double nz, double[] triangle) {}

    /**
     * @param full true for the whole sphere (the lower half is drawn too, for a boss on a peak where a half dome would hang in the air),
     *             false for the hemisphere standing on the ground ring
     */
    static List<Panel> panels(double radius, double height, int detail, boolean full) {
        List<double[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        icosahedron(vertices, faces);
        for (int i = 0; i < detail; i++) faces = subdivide(vertices, faces);

        List<Panel> out = new ArrayList<>();
        for (int[] face : faces) {
            double[] a = vertices.get(face[0]);
            double[] b = vertices.get(face[1]);
            double[] c = vertices.get(face[2]);
            if (!full && (a[1] < -EPSILON || b[1] < -EPSILON || c[1] < -EPSILON)) continue;   // the lower half
            out.add(panel(scale(a, radius, height), scale(b, radius, height), scale(c, radius, height), out.size()));
        }
        return out;
    }

    /**
     * The panels a player should have, with a margin so one standing on the edge of the range does not see panels flicker in and out:
     * every panel within {@code near} blocks, plus every panel they already have that is still within {@code far}. The player's position is
     * relative to the dome's centre.
     */
    static Set<Integer> visible(List<Panel> panels, Set<Integer> have, double px, double py, double pz, double near, double far) {
        Set<Integer> want = new HashSet<>();
        for (int i = 0; i < panels.size(); i++) {
            Panel p = panels.get(i);
            double dx = p.x() - px;
            double dy = p.y() - py;
            double dz = p.z() - pz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= near * near || (have.contains(i) && d2 <= far * far)) want.add(i);
        }
        return want;
    }

    /** The {@code count} panels closest to a point (relative to the centre), nearest first. */
    static List<Integer> nearest(List<Panel> panels, double px, double py, double pz, int count) {
        Map<Integer, Double> distance = new HashMap<>();
        for (int i = 0; i < panels.size(); i++) {
            Panel p = panels.get(i);
            distance.put(i, (p.x() - px) * (p.x() - px) + (p.y() - py) * (p.y() - py) + (p.z() - pz) * (p.z() - pz));
        }
        return distance.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(count).map(Map.Entry::getKey).toList();
    }

    private static Panel panel(double[] a0, double[] b0, double[] c0, int index) {
        // grow about the centroid so neighbours overlap a little
        double[] g = {(a0[0] + b0[0] + c0[0]) / 3, (a0[1] + b0[1] + c0[1]) / 3, (a0[2] + b0[2] + c0[2]) / 3};
        a0 = grow(a0, g);
        b0 = grow(b0, g);
        c0 = grow(c0, g);
        // the longest edge is the base, so the shear and the stretch stay small
        double ab = dist(a0, b0), bc = dist(b0, c0), ca = dist(c0, a0);
        double[] a, b, c;
        if (ab >= bc && ab >= ca) { a = a0; b = b0; c = c0; }
        else if (bc >= ca) { a = b0; b = c0; c = a0; }
        else { a = c0; b = a0; c = b0; }

        double[] x = unit(sub(b, a));
        double[] w = sub(c, a);
        double along = dot(w, x);
        double[] y = unit(new double[] {w[0] - x[0] * along, w[1] - x[1] * along, w[2] - x[2] * along});
        double[] z = cross(x, y);

        // texture triangle (base-left, base-right, apex) = quad points (-.5,-.5), (.5,-.5), (0,.5); the plane matrix taking them to a, b, c
        double width = dist(a, b);
        double tall = dot(w, y);
        double shear = along - 0.5 * width;
        double[][] m = {{width, shear}, {0, tall}};
        double[] centre = {
                a[0] + x[0] * 0.5 * (width + shear) + y[0] * 0.5 * tall,
                a[1] + x[1] * 0.5 * (width + shear) + y[1] * 0.5 * tall,
                a[2] + x[2] * 0.5 * (width + shear) + y[2] * 0.5 * tall};

        // m = rotate(phi) * diag(s1, s2) * rotate(theta)
        double e = (m[0][0] + m[1][1]) / 2, f = (m[0][0] - m[1][1]) / 2;
        double gg = (m[1][0] + m[0][1]) / 2, h = (m[1][0] - m[0][1]) / 2;
        double q = Math.hypot(e, h), r = Math.hypot(f, gg);
        double a1 = Math.atan2(gg, f), a2 = Math.atan2(h, e);
        double theta = (a2 - a1) / 2, phi = (a2 + a1) / 2;

        float[] tilt = quaternion(x, y, z);
        float[] left = multiply(tilt, new float[] {0f, 0f, (float) Math.sin(phi / 2), (float) Math.cos(phi / 2)});
        float[] right = {0f, 0f, (float) Math.sin(theta / 2), (float) Math.cos(theta / 2)};
        return new Panel(centre[0], centre[1], centre[2], left[0], left[1], left[2], left[3], right[0], right[1], right[2], right[3],
                (float) (q + r), (float) (q - r), (index * 7 + index / 5) % SWIRL_VARIANTS, z[0], z[1], z[2],
                new double[] {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]});
    }

    /** Rotation whose columns are the given axes, as (x, y, z, w). */
    static float[] quaternion(double[] x, double[] y, double[] z) {
        double m00 = x[0], m10 = x[1], m20 = x[2];
        double m01 = y[0], m11 = y[1], m21 = y[2];
        double m02 = z[0], m12 = z[1], m22 = z[2];
        double trace = m00 + m11 + m22;
        double qx, qy, qz, qw;
        if (trace > 0) {
            double s = 0.5 / Math.sqrt(trace + 1.0);
            qw = 0.25 / s;
            qx = (m21 - m12) * s;
            qy = (m02 - m20) * s;
            qz = (m10 - m01) * s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m00 - m11 - m22);
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m11 - m00 - m22);
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = 2.0 * Math.sqrt(1.0 + m22 - m00 - m11);
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        double len = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        return new float[] {(float) (qx / len), (float) (qy / len), (float) (qz / len), (float) (qw / len)};
    }

    /** {@code p * q} as quaternions (x, y, z, w): applies q first, then p. */
    static float[] multiply(float[] p, float[] q) {
        return new float[] {
                p[3] * q[0] + p[0] * q[3] + p[1] * q[2] - p[2] * q[1],
                p[3] * q[1] - p[0] * q[2] + p[1] * q[3] + p[2] * q[0],
                p[3] * q[2] + p[0] * q[1] - p[1] * q[0] + p[2] * q[3],
                p[3] * q[3] - p[0] * q[0] - p[1] * q[1] - p[2] * q[2]};
    }

    // ---- the icosphere ----

    /** An icosahedron standing on a vertex: one at the top, one at the bottom, and two rings of five at plus and minus 1/sqrt(5) of the height. */
    private static void icosahedron(List<double[]> vertices, List<int[]> faces) {
        double ring = 1.0 / Math.sqrt(5.0);
        double radius = 2.0 * ring;
        vertices.add(new double[] {0, 1, 0});                                   // 0: top
        for (int k = 0; k < 5; k++) {                                           // 1-5: upper ring
            double angle = Math.toRadians(72.0 * k);
            vertices.add(new double[] {radius * Math.cos(angle), ring, radius * Math.sin(angle)});
        }
        for (int k = 0; k < 5; k++) {                                           // 6-10: lower ring, turned half a step
            double angle = Math.toRadians(72.0 * k + 36.0);
            vertices.add(new double[] {radius * Math.cos(angle), -ring, radius * Math.sin(angle)});
        }
        vertices.add(new double[] {0, -1, 0});                                  // 11: bottom
        for (int k = 0; k < 5; k++) {
            int u = 1 + k, u2 = 1 + (k + 1) % 5, l = 6 + k, l2 = 6 + (k + 1) % 5;
            faces.add(new int[] {0, u, u2});        // top cap
            faces.add(new int[] {u, u2, l});        // band, pointing down
            faces.add(new int[] {l, l2, u2});       // band, pointing up
            faces.add(new int[] {11, l, l2});       // bottom cap
        }
    }

    private static List<int[]> subdivide(List<double[]> vertices, List<int[]> faces) {
        Map<Long, Integer> midpoints = new HashMap<>();
        List<int[]> out = new ArrayList<>();
        for (int[] f : faces) {
            int ab = midpoint(vertices, midpoints, f[0], f[1]);
            int bc = midpoint(vertices, midpoints, f[1], f[2]);
            int ca = midpoint(vertices, midpoints, f[2], f[0]);
            out.add(new int[] {f[0], ab, ca});
            out.add(new int[] {f[1], bc, ab});
            out.add(new int[] {f[2], ca, bc});
            out.add(new int[] {ab, bc, ca});
        }
        return out;
    }

    private static int midpoint(List<double[]> vertices, Map<Long, Integer> cache, int i, int j) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        Integer known = cache.get(key);
        if (known != null) return known;
        double[] a = vertices.get(i);
        double[] b = vertices.get(j);
        double[] m = unit(new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2});
        if (Math.abs(m[1]) < 1e-12) m[1] = 0.0;   // the equator must be exactly flat for the cut
        vertices.add(m);
        cache.put(key, vertices.size() - 1);
        return vertices.size() - 1;
    }

    // ---- small vector helpers ----

    private static double[] scale(double[] v, double radius, double height) {
        return new double[] {v[0] * radius, v[1] * height, v[2] * radius};
    }

    private static double[] grow(double[] v, double[] about) {
        return new double[] {about[0] + (v[0] - about[0]) * OVERLAP, about[1] + (v[1] - about[1]) * OVERLAP, about[2] + (v[2] - about[2]) * OVERLAP};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt(dot(sub(a, b), sub(a, b)));
    }

    private static double[] unit(double[] v) {
        double len = Math.sqrt(dot(v, v));
        return new double[] {v[0] / len, v[1] / len, v[2] / len};
    }
}
