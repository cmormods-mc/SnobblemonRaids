package com.cmormods.rankstacklimits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StackRedistributionPlannerTest {
    @Test
    void ninetyNineToSixtyFourSplitsWithoutLoss() {
        assertEquals(List.of(64, 35), StackRedistributionPlanner.splitCount(99, 64));
    }

    @Test
    void eightyToSixtyFourSplitsWithoutLoss() {
        assertEquals(List.of(64, 16), StackRedistributionPlanner.splitCount(80, 64));
    }

    @Test
    void alreadyValidStackRemainsSingleChunk() {
        assertEquals(List.of(64), StackRedistributionPlanner.splitCount(64, 64));
        assertEquals(List.of(35), StackRedistributionPlanner.splitCount(35, 64));
    }

    @Test
    void zeroProducesNoChunks() {
        assertEquals(List.of(), StackRedistributionPlanner.splitCount(0, 64));
    }

    @Test
    void allGeneratedChunksPreserveCountAndRespectLimit() {
        for (int limit = 1; limit <= 99; limit++) {
            for (int count = 0; count <= 500; count++) {
                List<Integer> chunks = StackRedistributionPlanner.splitCount(count, limit);
                assertEquals(count, chunks.stream().mapToInt(Integer::intValue).sum());
                assertTrue(chunks.stream().allMatch(chunk -> chunk >= 1 && chunk <= limit));
            }
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StackRedistributionPlanner.splitCount(-1, 64));
        assertThrows(IllegalArgumentException.class, () -> StackRedistributionPlanner.splitCount(64, 0));
    }
}
