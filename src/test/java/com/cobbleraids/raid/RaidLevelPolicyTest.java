package com.cobbleraids.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.CobbleRaidsConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A boss meets the group that recruited it, and never goes the other way.
 *
 * <p>The upward-only rule is the one worth guarding hardest. Loot is decided by rarity tier and
 * not by level, so a boss that could scale down would hand a low-level group a mythical's Mega
 * Stone odds off a trivial fight. Every test here that looks like it is about arithmetic is really
 * about that.
 */
class RaidLevelPolicyTest {

    private static final CobbleRaidsConfig.DynamicLevel ON = CobbleRaidsConfig.DynamicLevel.defaults();
    private static final CobbleRaidsConfig.DynamicLevel OFF =
            new CobbleRaidsConfig.DynamicLevel(false, 0, 100);

    @Test
    @DisplayName("a weaker party never makes a boss easier")
    void neverScalesDown() {
        // A legendary is level 100 in its definition. A party of level 20s still fights a level 100.
        assertEquals(100, RaidLevelPolicy.bossLevel(100, 20.0, ON));
        assertEquals(75, RaidLevelPolicy.bossLevel(75, 5.0, ON));
        assertEquals(85, RaidLevelPolicy.bossLevel(85, 84.9, ON));
    }

    @Test
    @DisplayName("a stronger party gets a harder boss")
    void scalesUpToTheParty() {
        // A starter raid is level 75; a party averaging 90 fights a level 90 one.
        assertEquals(90, RaidLevelPolicy.bossLevel(75, 90.0, ON));
        assertEquals(88, RaidLevelPolicy.bossLevel(75, 87.6, ON));
    }

    @Test
    @DisplayName("nothing exceeds the configured ceiling")
    void respectsTheCeiling() {
        assertEquals(100, RaidLevelPolicy.bossLevel(75, 140.0, ON));

        CobbleRaidsConfig.DynamicLevel capped = new CobbleRaidsConfig.DynamicLevel(true, 0, 90);
        assertEquals(90, RaidLevelPolicy.bossLevel(75, 100.0, capped));
    }

    @Test
    @DisplayName("a ceiling below the definition never drags a boss down")
    void ceilingCannotUndercutTheDefinition() {
        // An operator setting max_level to 60 must not turn every legendary into a level 60.
        CobbleRaidsConfig.DynamicLevel low = new CobbleRaidsConfig.DynamicLevel(true, 0, 60);

        assertEquals(100, RaidLevelPolicy.bossLevel(100, 100.0, low));
        assertEquals(100, RaidLevelPolicy.bossLevel(100, 20.0, low));
    }

    @Test
    @DisplayName("the offset shifts the target but cannot breach the floor")
    void offsetRespectsTheFloor() {
        CobbleRaidsConfig.DynamicLevel above = new CobbleRaidsConfig.DynamicLevel(true, 5, 100);
        CobbleRaidsConfig.DynamicLevel below = new CobbleRaidsConfig.DynamicLevel(true, -10, 100);

        assertEquals(95, RaidLevelPolicy.bossLevel(75, 90.0, above));
        // The party averages 90 and the offset asks for 80, which is still above the floor.
        assertEquals(80, RaidLevelPolicy.bossLevel(75, 90.0, below));
        // Here the offset would ask for 70, below the definition's 75. The floor wins.
        assertEquals(75, RaidLevelPolicy.bossLevel(75, 80.0, below));
    }

    @Test
    @DisplayName("switched off, a boss is exactly what its definition says")
    void disabledChangesNothing() {
        assertEquals(75, RaidLevelPolicy.bossLevel(75, 100.0, OFF));
        assertEquals(75, RaidLevelPolicy.bossLevel(75, 100.0, null));
    }

    @Test
    @DisplayName("an unreadable party leaves the boss alone rather than dropping it to nothing")
    void emptyPartyIsSafe() {
        assertEquals(100, RaidLevelPolicy.bossLevel(100, 0.0, ON));
        assertEquals(100, RaidLevelPolicy.bossLevel(100, -5.0, ON));
        assertEquals(0.0, RaidLevelPolicy.average(List.of()));
        assertEquals(0.0, RaidLevelPolicy.average(null));
    }

    @Test
    @DisplayName("the average spans every battle-ready Pokemon, not each player's best")
    void averageIsFlatAcrossTheGroup() {
        // One player fielding six level 100s and one fielding a single level 10 average 87.1, not
        // 55 -- a group is as strong as what it can actually put on the field.
        assertEquals(87.14, RaidLevelPolicy.average(List.of(100, 100, 100, 100, 100, 100, 10)), 0.01);
        assertEquals(50.0, RaidLevelPolicy.average(List.of(40, 60)));
    }

    @Test
    @DisplayName("a level is never below its floor whatever the inputs")
    void floorHoldsAcrossTheRange() {
        for (int definitionLevel : new int[] {75, 85, 100}) {
            for (double average = 0.0; average <= 120.0; average += 3.0) {
                int level = RaidLevelPolicy.bossLevel(definitionLevel, average, ON);

                assertTrue(level >= definitionLevel,
                        "definition " + definitionLevel + " with party " + average + " gave " + level);
                assertTrue(level <= 100, "level " + level + " exceeds the Cobblemon maximum");
            }
        }
    }
}
