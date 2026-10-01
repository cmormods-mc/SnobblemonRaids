package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BossBuyBackLevelTest {

    @Test
    @DisplayName("a boss raised to level 100 for a full party is bought back at its definition's level 25")
    void aRaisedBossComesBackAtItsBaseLevel() {
        assertEquals(25, BossSnapshotService.capLevel(100, List.of(25)));
        assertEquals(25, BossSnapshotService.capLevel(35, List.of(25)), "a renowned starter's bonus levels too");
    }

    @Test
    @DisplayName("a boss never comes back higher than it was saved")
    void neverRaisesALevel() {
        assertEquals(20, BossSnapshotService.capLevel(20, List.of(25)));
        assertEquals(25, BossSnapshotService.capLevel(25, List.of(25)));
    }

    @Test
    @DisplayName("a species with two definitions comes back as its gentlest self")
    void usesTheLowestDefinition() {
        assertEquals(25, BossSnapshotService.capLevel(100, List.of(50, 25, 70)));
    }

    @Test
    @DisplayName("no definition to read, or a nonsense one, leaves the saved level alone")
    void noDefinitionLeavesTheLevel() {
        assertEquals(100, BossSnapshotService.capLevel(100, List.of()));
        assertEquals(100, BossSnapshotService.capLevel(100, List.of(0, -5)));
    }
}
