package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.worldboss.DomeGeometry.Panel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomeGeometryTest {

    /** Rotates v by the unit quaternion (x, y, z, w). */
    private static double[] rotate(double qx, double qy, double qz, double qw, double[] v) {
        double tx = 2 * (qy * v[2] - qz * v[1]);
        double ty = 2 * (qz * v[0] - qx * v[2]);
        double tz = 2 * (qx * v[1] - qy * v[0]);
        return new double[] {
                v[0] + qw * tx + (qy * tz - qz * ty),
                v[1] + qw * ty + (qz * tx - qx * tz),
                v[2] + qw * tz + (qx * ty - qy * tx)};
    }

    /** Where the display entity puts a point of its quad: translation, then left rotation, scale, right rotation, as Minecraft composes them. */
    private static double[] place(Panel p, double u, double v) {
        double[] r = rotate(p.rx(), p.ry(), p.rz(), p.rw(), new double[] {u, v, 0});
        double[] scaled = {r[0] * p.s1(), r[1] * p.s2(), r[2]};
        double[] l = rotate(p.lx(), p.ly(), p.lz(), p.lw(), scaled);
        return new double[] {p.x() + l[0], p.y() + l[1], p.z() + l[2]};
    }

    @Test
    void theHemisphereHasAFlatBaseAndATenthOfTheTrianglesPerDetailStep() {
        assertEquals(40, DomeGeometry.panels(128, 60, 1, false).size(), "half of 80");
        assertEquals(160, DomeGeometry.panels(128, 60, 2, false).size(), "half of 320");
        assertEquals(640, DomeGeometry.panels(128, 60, 3, false).size(), "half of 1280");
        for (Panel p : DomeGeometry.panels(128, 60, 2, false)) {
            // the overlap growth can push a base corner a fraction of a block under the ring, never more
            for (int i = 1; i < 9; i += 3) assertTrue(p.triangle()[i] > -2.0, "nothing far below the ground ring: " + p.triangle()[i]);
        }
    }

    @Test
    void theFullSphereIsTwiceTheHemisphereAndReachesAsFarDownAsUp() {
        assertEquals(80, DomeGeometry.panels(128, 60, 1, true).size());
        assertEquals(320, DomeGeometry.panels(128, 60, 2, true).size());
        double low = 0, high = 0;
        for (Panel p : DomeGeometry.panels(128, 60, 2, true)) {
            for (int i = 1; i < 9; i += 3) {
                low = Math.min(low, p.triangle()[i]);
                high = Math.max(high, p.triangle()[i]);
            }
        }
        assertEquals(high, -low, 1.0, "as deep as it is tall");
        assertTrue(high > 60 && high < 66, "the apex reaches the height, a little more for the overlap: " + high);
    }

    @Test
    void everyFullSpherePanelMapsOntoItsTriangle() {
        for (Panel p : DomeGeometry.panels(128, 60, 2, true)) {
            double[] t = p.triangle();
            double[][] landed = {place(p, -0.5, -0.5), place(p, 0.5, -0.5), place(p, 0.0, 0.5)};
            for (int corner = 0; corner < 3; corner++) {
                for (int axis = 0; axis < 3; axis++) assertEquals(t[corner * 3 + axis], landed[corner][axis], 0.02);
            }
        }
    }

    @Test
    void theBaseIsAClosedRingOfEdgesAtGroundLevel() {
        // every triangle on the ring has exactly one edge at y = 0, and the ring is 5 * 2^detail edges long
        int onRing = 0;
        for (Panel p : DomeGeometry.panels(128, 60, 2, false)) {
            double[] t = p.triangle();
            int low = 0;
            for (int i = 1; i < 9; i += 3) if (t[i] < 0.5) low++;
            if (low >= 2) onRing++;
        }
        assertEquals(20, onRing, "5 * 2^2 triangles stand on the ground");
    }

    @Test
    void theTransformPutsTheTextureTriangleExactlyOnTheRealTriangle() {
        // base-left (-.5,-.5), base-right (.5,-.5), apex (0,.5) of the quad must land on the panel's three corners
        for (int detail = 1; detail <= 3; detail++) {
            for (Panel p : DomeGeometry.panels(128, 60, detail, false)) {
                double[] t = p.triangle();
                double[][] landed = {place(p, -0.5, -0.5), place(p, 0.5, -0.5), place(p, 0.0, 0.5)};
                for (int corner = 0; corner < 3; corner++) {
                    for (int axis = 0; axis < 3; axis++) {
                        assertEquals(t[corner * 3 + axis], landed[corner][axis], 0.02,
                                "detail " + detail + " corner " + corner + " axis " + axis);
                    }
                }
            }
        }
    }

    @Test
    void theRotationsAreUnitQuaternionsAndTheStretchIsPositiveAndSensible() {
        for (Panel p : DomeGeometry.panels(128, 60, 2, false)) {
            assertEquals(1.0, Math.sqrt(p.lx() * p.lx() + p.ly() * p.ly() + p.lz() * p.lz() + p.lw() * p.lw()), 1e-4);
            assertEquals(1.0, Math.sqrt(p.rx() * p.rx() + p.ry() * p.ry() + p.rz() * p.rz() + p.rw() * p.rw()), 1e-4);
            assertEquals(1.0, Math.sqrt(p.nx() * p.nx() + p.ny() * p.ny() + p.nz() * p.nz()), 1e-9);
            assertTrue(p.s1() > 5 && p.s1() < 90, "stretch " + p.s1());
            assertTrue(p.s2() > 5 && p.s2() < 90, "stretch " + p.s2());
            assertTrue(p.variant() >= 0 && p.variant() < DomeGeometry.SWIRL_VARIANTS);
        }
    }

    @Test
    void neighbouringPanelsMeetSoThereAreNoHoles() {
        // every panel corner is shared with at least one other panel, so the mesh is closed apart from its own rim
        List<Panel> panels = DomeGeometry.panels(128, 60, 2, false);
        for (Panel p : panels) {
            double[] t = p.triangle();
            for (int corner = 0; corner < 3; corner++) {
                double cx = t[corner * 3], cy = t[corner * 3 + 1], cz = t[corner * 3 + 2];
                int near = 0;
                for (Panel other : panels) {
                    double[] o = other.triangle();
                    for (int k = 0; k < 3; k++) {
                        if (Math.abs(o[k * 3] - cx) < 4 && Math.abs(o[k * 3 + 1] - cy) < 4 && Math.abs(o[k * 3 + 2] - cz) < 4) near++;
                    }
                }
                assertTrue(near >= 3, "a corner is shared by several panels: " + near);
            }
        }
    }

    @Test
    void theVariantsAreMixedSoNeighboursDoNotPulseInStep() {
        Set<Integer> seen = new HashSet<>();
        DomeGeometry.panels(128, 60, 2, false).forEach(p -> seen.add(p.variant()));
        assertEquals(DomeGeometry.SWIRL_VARIANTS, seen.size());
    }

    @Test
    void aPlayerSeesNearPanelsAndKeepsFarOnesUntilTheyAreReallyFar() {
        List<Panel> panels = DomeGeometry.panels(128, 60, 2, false);
        Set<Integer> atEdge = DomeGeometry.visible(panels, Set.of(), 128, 0, 0, 60, 90);
        assertFalse(atEdge.isEmpty());
        assertTrue(atEdge.size() < panels.size(), "not the whole dome from the edge");
        assertTrue(DomeGeometry.visible(panels, Set.of(), 0, 0, 0, 60, 90).isEmpty(), "standing at the centre the wall is 128 blocks away");
        // a panel already shown is kept past the near range, so standing on the boundary does not flicker
        int shownIndex = atEdge.iterator().next();
        Panel p = panels.get(shownIndex);
        double step = 75;   // between near (60) and far (90)
        Set<Integer> moved = DomeGeometry.visible(panels, Set.of(shownIndex), p.x() + step, p.y(), p.z(), 60, 90);
        assertTrue(moved.contains(shownIndex), "kept inside the far range");
        assertFalse(DomeGeometry.visible(panels, Set.of(), p.x() + step, p.y(), p.z(), 60, 90).contains(shownIndex), "not added from outside the near range");
        assertFalse(DomeGeometry.visible(panels, Set.of(shownIndex), p.x() + 120, p.y(), p.z(), 60, 90).contains(shownIndex), "dropped beyond the far range");
    }

    @Test
    void nearestReturnsTheClosestPanelsFirst() {
        List<Panel> panels = DomeGeometry.panels(128, 60, 2, false);
        Panel target = panels.get(5);
        List<Integer> three = DomeGeometry.nearest(panels, target.x(), target.y(), target.z(), 3);
        assertEquals(3, three.size());
        assertEquals(5, three.get(0));
    }
}
