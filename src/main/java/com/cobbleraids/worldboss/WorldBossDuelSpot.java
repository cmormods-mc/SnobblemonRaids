package com.cobbleraids.worldboss;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a duel's private boss may stand, as a list of ground positions round the player, best first. Pure geometry, so the one rule that
 * matters can be tested: <b>no spot is ever the player's own, or near it</b>.
 *
 * <p>That rule is a hard one. Cobblemon places the player's Pokemon for a battle by dividing a minimum distance by the distance between the player
 * and the boss; with the boss exactly on the player that is a division by zero, the position becomes NaN, and the block raycast it feeds then runs
 * forever on the server thread (two freezes of a test server, found in a thread dump). So every candidate here is at least {@link #NEAREST}
 * blocks away, in a fixed order that starts in front of the player and widens outwards, and the caller picks the first one the terrain allows.
 */
public final class WorldBossDuelSpot {
    /** The closest a candidate ever is to the player, horizontally. */
    public static final double NEAREST = 6.0;
    private static final double[] RINGS = {NEAREST, 8.0, 10.0};
    private static final double[] TURNS_DEGREES = {0, 45, -45, 90, -90, 135, -135, 180};

    private WorldBossDuelSpot() {}

    /**
     * @param lookX the horizontal part of where the player faces; a zero or non-finite look falls back to east
     * @return (x, z) pairs, the first straight ahead at {@link #NEAREST} blocks, then the sides and behind, then the wider rings
     */
    public static List<double[]> candidates(double playerX, double playerZ, double lookX, double lookZ) {
        double length = Math.hypot(lookX, lookZ);
        double fx = 1.0;
        double fz = 0.0;
        if (Double.isFinite(length) && length > 1.0e-6) {
            fx = lookX / length;
            fz = lookZ / length;
        }
        var out = new ArrayList<double[]>();
        if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)) return out;
        for (double ring : RINGS) {
            for (double turn : TURNS_DEGREES) {
                double angle = Math.toRadians(turn);
                double dx = fx * Math.cos(angle) - fz * Math.sin(angle);
                double dz = fx * Math.sin(angle) + fz * Math.cos(angle);
                out.add(new double[] {playerX + dx * ring, playerZ + dz * ring});
            }
        }
        return out;
    }
}
