package com.cobbleraids.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.spawn.RaidSpawnHistory;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@code /cobbleraids debug history} collapses consecutive entries that share an outcome, player and
 * dimension into one row with a count -- {@link RaidAdminDebugOps#sameRun} is the predicate that
 * decides whether two entries belong to the same run. Tick is deliberately not part of the
 * comparison: two entries a run apart but seconds apart in tick still collapse together.
 */
class RaidAdminDebugOpsTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");

    private static RaidSpawnHistory.Entry entry(long tick, String player, ResourceLocation dimension,
                                                  RaidSpawnHistory.Outcome outcome) {
        return new RaidSpawnHistory.Entry(tick, player, dimension, outcome, "detail");
    }

    @Test
    void sameOutcomePlayerAndDimensionIsTheSameRunRegardlessOfTick() {
        RaidSpawnHistory.Entry a = entry(0, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.NO_VALID_TERRAIN);
        RaidSpawnHistory.Entry b = entry(400, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.NO_VALID_TERRAIN);

        assertTrue(RaidAdminDebugOps.sameRun(a, b));
    }

    @Test
    void aDifferentOutcomeBreaksTheRun() {
        RaidSpawnHistory.Entry a = entry(0, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.NO_VALID_TERRAIN);
        RaidSpawnHistory.Entry b = entry(20, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.SUCCESS);

        assertFalse(RaidAdminDebugOps.sameRun(a, b));
    }

    @Test
    void aDifferentPlayerBreaksTheRun() {
        RaidSpawnHistory.Entry a = entry(0, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.TOO_CLOSE_TO_EXISTING);
        RaidSpawnHistory.Entry b = entry(20, "Misty", OVERWORLD, RaidSpawnHistory.Outcome.TOO_CLOSE_TO_EXISTING);

        assertFalse(RaidAdminDebugOps.sameRun(a, b));
    }

    @Test
    void aDifferentDimensionBreaksTheRun() {
        RaidSpawnHistory.Entry a = entry(0, "Ash", OVERWORLD, RaidSpawnHistory.Outcome.PER_DIMENSION_CAP);
        RaidSpawnHistory.Entry b = entry(20, "Ash", NETHER, RaidSpawnHistory.Outcome.PER_DIMENSION_CAP);

        assertFalse(RaidAdminDebugOps.sameRun(a, b));
    }
}
