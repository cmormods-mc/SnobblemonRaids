package com.cobbleraids.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three-player raid that could not attack.
 *
 * <p>Actors are p1a, p2b and p3c with the boss at p4a. A player's client does not know the battle
 * is a raid -- RaidRegistry is server state -- so it falls back to Cobblemon's mirrored-field
 * maths and nominated p2b, a teammate. Every move came back "Invalid action choice", and the raid
 * could not proceed.
 */
class RaidMoveTargetingTest {

    /** The boss, as the only legal opponent a player has. */
    private static final List<String> BOSS_ONLY = List.of("p4a");

    @Test
    @DisplayName("a move aimed at a teammate is repointed at the boss")
    void allyTargetIsCorrected() {
        // Exactly the live failure: p1a's Sprigatito aiming Leafage at p2b's Zacian.
        assertEquals("p4a", RaidMoveTargeting.correctedTarget("p2b", BOSS_ONLY));
        assertEquals("p4a", RaidMoveTargeting.correctedTarget("p3c", BOSS_ONLY));
    }

    @Test
    @DisplayName("a move aimed at a slot nobody occupies is repointed at the boss")
    void absentSlotIsCorrected() {
        assertEquals("p4a", RaidMoveTargeting.correctedTarget("p2a", BOSS_ONLY));
        assertEquals("p4a", RaidMoveTargeting.correctedTarget("p9z", BOSS_ONLY));
    }

    @Test
    @DisplayName("a move already aimed at the boss is left exactly as the player sent it")
    void legalTargetIsUntouched() {
        assertNull(RaidMoveTargeting.correctedTarget("p4a", BOSS_ONLY));
    }

    @Test
    @DisplayName("the boss picking among players keeps whichever it legally chose")
    void bossChoiceIsRespected() {
        // From the boss's side every player is a legal opponent, and it does have a real choice.
        List<String> players = List.of("p1a", "p2b", "p3c");

        assertNull(RaidMoveTargeting.correctedTarget("p2b", players));
        assertNull(RaidMoveTargeting.correctedTarget("p3c", players));
        assertEquals("p1a", RaidMoveTargeting.correctedTarget("p4a", players));
    }

    @Test
    @DisplayName("nothing to aim at leaves the move alone rather than inventing a target")
    void noOpponentsLeavesItAlone() {
        assertNull(RaidMoveTargeting.correctedTarget("p2b", List.of()));
        assertNull(RaidMoveTargeting.correctedTarget("p2b", null));
    }

    @Test
    @DisplayName("a move that carries no target is not given one")
    void untargetedMoveStaysUntargeted() {
        // Showdown resolves these itself, and raid-patch.js aims them at the boss regardless.
        assertNull(RaidMoveTargeting.correctedTarget(null, BOSS_ONLY));
    }
}
