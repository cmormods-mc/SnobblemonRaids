package com.cobbleraids.api.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules an owner may impose on an owned encounter's battle, and what they refuse to be. */
class EncounterRulesTest {

    @Test
    @DisplayName("the neutral value is an ordinary battle")
    void noneRestrictsNothing() {
        EncounterRules none = EncounterRules.none();

        assertTrue(none.bannedMoves().isEmpty());
        assertTrue(none.switchingAllowed(), "a caller that says nothing must not forbid switching");
        assertTrue(none.itemsAllowed());
        assertEquals(Optional.empty(), none.weather());
        assertFalse(none.restrictsAnything());
    }

    @Test
    @DisplayName("move ids are normalised, so content can be written the way people write it")
    void movesAreNormalised() {
        EncounterRules rules = EncounterRules.none().withBannedMoves(List.of("Protect", " protect ", "RECOVER"));

        assertEquals(List.of("protect", "recover"), rules.bannedMoves(), "trimmed, lowercased, deduplicated");
        assertTrue(rules.bans("PROTECT"), "and matched however the caller asks");
        assertFalse(rules.bans("tackle"));
    }

    @Test
    @DisplayName("an id that is not a Showdown id is refused at construction")
    void idsAreValidated() {
        // These reach Showdown by being written into the >start payload's format JSON. An id
        // carrying a quote or a brace would corrupt the payload for the whole battle rather than
        // merely failing to apply, so it is refused here, where the message can name the field.
        assertThrows(IllegalArgumentException.class,
                () -> EncounterRules.none().withWeather(Optional.of("rain dance")));
        assertThrows(IllegalArgumentException.class,
                () -> EncounterRules.none().withWeather(Optional.of("rain\",\"x\":\"")));
        assertThrows(IllegalArgumentException.class,
                () -> EncounterRules.none().withTerrain(Optional.of("Electric")));
        assertThrows(IllegalArgumentException.class,
                () -> EncounterRules.none().withBannedMoves(List.of("close combat")));
    }

    @Test
    @DisplayName("a real Showdown id is accepted")
    void realIdsPass() {
        EncounterRules rules = EncounterRules.none()
                .withWeather(Optional.of("raindance"))
                .withTerrain(Optional.of("electricterrain"));

        assertEquals(Optional.of("raindance"), rules.weather());
        assertEquals(Optional.of("electricterrain"), rules.terrain());
        assertTrue(rules.restrictsAnything());
    }

    @Test
    @DisplayName("blank and null moves are dropped rather than becoming invalid ids")
    void blanksDropped() {
        EncounterRules rules = EncounterRules.none().withBannedMoves(java.util.Arrays.asList("", "  ", null));
        assertTrue(rules.bannedMoves().isEmpty());
    }

    @Test
    @DisplayName("each restriction shows up on its own")
    void eachRestrictionCounts() {
        assertTrue(EncounterRules.none().withSwitching(false).restrictsAnything());
        assertTrue(EncounterRules.none().withItems(false).restrictsAnything());
        assertTrue(EncounterRules.none().withBannedMoves(List.of("protect")).restrictsAnything());
    }

    @Test
    @DisplayName("a withers changes one thing and leaves the rest alone")
    void withersAreNarrow() {
        EncounterRules rules = EncounterRules.none()
                .withBannedMoves(List.of("protect"))
                .withSwitching(false)
                .withWeather(Optional.of("sandstorm"));

        assertEquals(List.of("protect"), rules.bannedMoves());
        assertFalse(rules.switchingAllowed());
        assertTrue(rules.itemsAllowed(), "items were never mentioned, so they are still allowed");
        assertEquals(Optional.of("sandstorm"), rules.weather());
    }

    @Test
    @DisplayName("the constructor that predates rules still exists, so old callers still compile")
    void requestKeepsItsOlderConstructor() {
        // EncounterRequest cannot be built in a unit test -- it holds a live level and live players
        // -- so this checks the promise that can be checked: adding `rules` did not remove the
        // nine-argument form. An owner who does not want to change how the battle is fought should
        // not have to say so, and a consumer built against 0.8.94 should not stop compiling.
        long nineArg = java.util.Arrays.stream(EncounterRequest.class.getConstructors())
                .filter(constructor -> constructor.getParameterCount() == 9)
                .count();
        assertEquals(1, nineArg, "the pre-rules constructor is part of the published API");

        long tenArg = java.util.Arrays.stream(EncounterRequest.class.getConstructors())
                .filter(constructor -> constructor.getParameterCount() == 10)
                .count();
        assertEquals(1, tenArg, "and the canonical one takes rules");
    }
}
