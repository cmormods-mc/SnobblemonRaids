package com.cobbleraids.worldboss;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the particle wall of a World Boss's zone is drawn for one player. Pure geometry, so the maths can be tested without a server.
 *
 * <p>The zone is the circle of {@code radius} blocks around the boss: a player inside it may duel, which is the same test
 * {@link WorldBossService} applies. The wall is drawn only for a player near that circle, and only the stretch of it within {@code view} blocks
 * of them, so the cost grows with the players standing at the edge and not with the size of the zone.
 *
 * <p>The columns sit on a fixed grid of angles around the boss, not relative to the player, so the wall stays where it is as someone walks along
 * it instead of shimmering.
 */
public final class WorldBossBorder {
    /** The most columns one player is sent in one pass, whatever the settings. */
    public static final int MAX_COLUMNS = 48;

    private WorldBossBorder() {}

    /**
     * The (x, z) of each particle column of the wall that is within {@code view} blocks of the player, in order round the circle.
     *
     * @param spacing blocks between neighbouring columns along the circle
     * @return nothing when the player is further than {@code view} from the circle (inside or outside it)
     */
    public static List<double[]> columns(double centerX, double centerZ, double radius, double playerX, double playerZ, double view, double spacing) {
        var out = new ArrayList<double[]>();
        if (!(radius > 0) || !(view > 0) || !(spacing > 0)) return out;
        double dx = playerX - centerX;
        double dz = playerZ - centerZ;
        double d = Math.hypot(dx, dz);
        if (Math.abs(d - radius) > view) return out;
        double halfArc;                       // how far round the circle, either side of the player's bearing, is still within view
        double bearing = d < 1.0e-6 ? 0.0 : Math.atan2(dz, dx);
        if (d < 1.0e-6) {
            halfArc = Math.PI;                // standing at the centre: every point of the circle is `radius` away
        } else {
            double cos = (d * d + radius * radius - view * view) / (2.0 * d * radius);
            if (cos >= 1.0) return out;
            halfArc = cos <= -1.0 ? Math.PI : Math.acos(cos);
        }
        double step = spacing / radius;
        long first = (long) Math.ceil((bearing - halfArc) / step);
        long last = (long) Math.floor((bearing + halfArc) / step);
        long span = last - first + 1;
        if (span > MAX_COLUMNS) {             // never more than the cap: keep the columns nearest the player's bearing
            long middle = Math.round(bearing / step);
            first = middle - MAX_COLUMNS / 2;
            last = first + MAX_COLUMNS - 1;
        }
        for (long k = first; k <= last; k++) {
            double angle = k * step;
            out.add(new double[] {centerX + Math.cos(angle) * radius, centerZ + Math.sin(angle) * radius});
        }
        return out;
    }

    /** True when the player stands inside the zone (the boundary counts as inside, matching the duel range check). */
    public static boolean inside(double centerX, double centerZ, double radius, double playerX, double playerZ) {
        double dx = playerX - centerX;
        double dz = playerZ - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }
}
