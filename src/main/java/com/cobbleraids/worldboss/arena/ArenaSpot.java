package com.cobbleraids.worldboss.arena;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Finds a place for a warping player to stand. Pure: the world is asked through two predicates, so it can be tested without one. */
public final class ArenaSpot {
    private ArenaSpot() {}

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    /**
     * The first standable spot on a ring round {@code center}: a block the player can stand IN (this and the one above are passable) with a
     * solid block below, looking up to {@code vertical} blocks either way, trying each distance in {@code distances} and the eight directions.
     *
     * @param solid    (x, y, z) packed as a BlockPos: is this block solid to stand on
     * @param passable is this block free to stand in
     */
    public static Optional<BlockPos> near(BlockPos center, int[] distances, int vertical, java.util.function.Predicate<BlockPos> solid,
                                          java.util.function.Predicate<BlockPos> passable) {
        for (int distance : distances) {
            for (int[] d : DIRECTIONS) {
                int x = center.getX() + d[0] * distance, z = center.getZ() + d[1] * distance;
                for (int dy = vertical; dy >= -vertical; dy--) {
                    BlockPos feet = new BlockPos(x, center.getY() + dy, z);
                    if (passable.test(feet) && passable.test(feet.above()) && solid.test(feet.below())) return Optional.of(feet);
                }
            }
        }
        return Optional.empty();
    }
}
