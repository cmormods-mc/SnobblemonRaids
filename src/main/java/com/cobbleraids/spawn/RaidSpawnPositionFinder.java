package com.cobbleraids.spawn;

import com.cobbleraids.config.CobbleRaidsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Finds a conservative loaded-chunk surface position around a player without touching Cobblemon's normal spawn pools. */
public final class RaidSpawnPositionFinder {
    private RaidSpawnPositionFinder() {}

    public static Optional<BlockPos> findLand(ServerLevel level, ServerPlayer player, CobbleRaidsConfig.NaturalSpawning config) {
        BlockPos center = player.blockPosition();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double min = config.minDistanceFromPlayer();
        double max = config.maxDistanceFromPlayer();

        for (int attempt = 0; attempt < config.locationAttempts(); attempt++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            // Area-uniform annulus sampling rather than clustering all candidates near the minimum radius.
            double radius = Math.sqrt(random.nextDouble(min * min, max * max));
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos probe = new BlockPos(x, center.getY(), z);
            if (!level.hasChunkAt(probe)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);

            if (!level.getWorldBorder().isWithinBounds(candidate)) continue;
            if (!isSimpleSurfaceSpawn(level, candidate)) continue;
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean isSimpleSurfaceSpawn(ServerLevel level, BlockPos pos) {
        BlockPos groundPos = pos.below();
        BlockPos headPos = pos.above();
        var ground = level.getBlockState(groundPos);
        var feet = level.getBlockState(pos);
        var head = level.getBlockState(headPos);

        if (ground.isAir() || ground.getCollisionShape(level, groundPos).isEmpty()) return false;
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        if (!feet.getCollisionShape(level, pos).isEmpty()) return false;
        if (!head.getCollisionShape(level, headPos).isEmpty()) return false;
        return true;
    }
}
