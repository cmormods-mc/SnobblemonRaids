package com.cobbleraids.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one rule in this service that has been got wrong before, in three different places: a tracked
 * boss that does not resolve is not a boss that has gone away.
 *
 * <p>Phase 32 established the distinction for the spawn scheduler, {@code ActiveRaidSpawnTracker}
 * now enforces it there, and the glow service was still failing it -- dropping any boss whose chunk
 * had unloaded. That was permanent, because registration only happens at spawn: the boss came back
 * when the chunk reloaded and never glowed again.
 */
class RaidBossGlowServiceTest {

    @Test
    @DisplayName("a boss whose chunk is not loaded stays tracked")
    void unresolvedIsKept() {
        // The whole bug in one assertion. "Not loaded" and "no longer exists" are indistinguishable
        // from a failed lookup, and only one of them means the boss is gone.
        assertFalse(RaidBossGlowService.shouldUntrack(false, false, false));
    }

    @Test
    @DisplayName("a boss that resolved and reports removed is dropped")
    void resolvedAndRemovedIsDropped() {
        assertTrue(RaidBossGlowService.shouldUntrack(true, true, true));
    }

    @Test
    @DisplayName("an entity that is no longer a raid boss is dropped")
    void demotedBossIsDropped() {
        // The marker can be gone while the entity lives -- an admin clearing it, or another mod
        // rewriting the entity's tags. Glowing it after that would tint an ordinary Pokemon.
        assertTrue(RaidBossGlowService.shouldUntrack(true, false, false));
    }

    @Test
    @DisplayName("a live, marked, loaded boss is kept")
    void healthyBossIsKept() {
        assertFalse(RaidBossGlowService.shouldUntrack(true, false, true));
    }

    @Test
    @DisplayName("nothing about an unresolved boss can cause it to be dropped")
    void unresolvedIsNeverDropped() {
        // Guards against a future reader "simplifying" the predicate by dropping the resolved check:
        // when the lookup failed, the other two flags say nothing at all and must not be consulted.
        for (boolean removed : new boolean[] {false, true}) {
            for (boolean stillBoss : new boolean[] {false, true}) {
                assertFalse(RaidBossGlowService.shouldUntrack(false, removed, stillBoss),
                        "unresolved must never be dropped (removed=" + removed + ", boss=" + stillBoss + ")");
            }
        }
    }
}
