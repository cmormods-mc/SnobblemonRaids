package com.cobbleraids.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared health pool now answers to two things: how many players turned up, which it always
 * did, and what level the boss ended up fighting at, which is new.
 *
 * <p>The level half exists because a strong party can raise a boss while the pool stayed a flat
 * number from the definition. A level 100 group meeting a level 25 starter boss would raise it to
 * 100 and then delete it in eight turns, Mega Stone and all.
 */
class RaidScalingPolicyTest {

    /** A retuned starter boss with three players: 550 base, +65% per extra player. */
    private static final long STARTER_TRIO = 1265L;
    private static final int STARTER_LEVEL = 25;

    @Test
    @DisplayName("a boss at its definition level has exactly the pool its definition asks for")
    void unscaledLevelChangesNothing() {
        assertEquals(STARTER_TRIO, RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, STARTER_LEVEL));
    }

    @Test
    @DisplayName("a renowned hp_pool boon raises the final pool by its share, and no bonus changes nothing")
    void renownRaisesThePool() {
        assertEquals(1455L, RaidScalingPolicy.forRenown(STARTER_TRIO, 0.15));
        assertEquals(STARTER_TRIO, RaidScalingPolicy.forRenown(STARTER_TRIO, 0.0));
        assertEquals(STARTER_TRIO, RaidScalingPolicy.forRenown(STARTER_TRIO, Double.NaN));
        assertEquals(Long.MAX_VALUE, RaidScalingPolicy.forRenown(Long.MAX_VALUE - 1_000, 0.5), "saturates, never wraps");
        // Applied after the level factor, so the bonus stays the same share of whatever pool results.
        long raised = RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, 100);
        assertEquals(Math.round(raised * 1.15), RaidScalingPolicy.forRenown(raised, 0.15));
    }

    @Test
    @DisplayName("raising the level raises the pool in proportion")
    void poolFollowsTheLevel() {
        // Four times the level, four times the pool -- the party that raised it hits about four
        // times as hard.
        assertEquals(STARTER_TRIO * 4, RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, 100));
        assertEquals(STARTER_TRIO * 2, RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, 50));
    }

    @Test
    @DisplayName("a level below the definition never shrinks the pool")
    void poolNeverShrinks() {
        for (int level : new int[] {24, 10, 1, 0, -5}) {
            assertEquals(STARTER_TRIO, RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, level),
                    "level " + level + " should leave the pool alone");
        }
    }

    @Test
    @DisplayName("a definition with no level is left alone rather than dividing by zero")
    void zeroDefinitionLevelIsSafe() {
        assertEquals(STARTER_TRIO, RaidScalingPolicy.forLevel(STARTER_TRIO, 0, 100));
        assertEquals(STARTER_TRIO, RaidScalingPolicy.forLevel(STARTER_TRIO, -1, 100));
    }

    @Test
    @DisplayName("a fight stays roughly the same length however strong the party is")
    void fightLengthHoldsAcrossLevels() {
        // The point of the whole thing. Damage output rises with level, so the pool has to as
        // well, or a strong party finishes a low-tier raid before it has started.
        double atDefinition = turns(RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, 25), 25);
        double atHundred = turns(RaidScalingPolicy.forLevel(STARTER_TRIO, STARTER_LEVEL, 100), 100);

        assertTrue(Math.abs(atDefinition - atHundred) < atDefinition * 0.35,
                "a level 25 party needs " + Math.round(atDefinition) + " turns and a level 100 party "
                        + Math.round(atHundred) + "; those should be comparable");
    }

    @Test
    @DisplayName("the retuned starter tier is a fight, not a chore")
    void starterTierIsWinnable() {
        // The raid that prompted this: three players on level 10 Pokemon could not scratch a level
        // 75 boss with a 4600 pool. At the retuned numbers the same group has a real fight.
        double turnsForLowLevelParty = turns(STARTER_TRIO, 25);

        assertTrue(turnsForLowLevelParty > 5 && turnsForLowLevelParty < 60,
                "a level 25 trio needs " + Math.round(turnsForLowLevelParty) + " turns");
    }

    /** Rough turns to clear a pool, using the standard damage curve for a same-level attacker. */
    private static double turns(long pool, int level) {
        double perHit = ((2 * level / 5.0 + 2) * 60) / 50 + 2;
        return pool / (perHit * 3);
    }
}
