package com.cobbleraids.api.encounter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The request checks that need no server: how many players, what level, what pool. */
class EncounterRequestShapeTest {

    @Test
    @DisplayName("an encounter holds one to four players")
    void playerCount() {
        assertThrows(IllegalArgumentException.class, () -> EncounterRequest.validateShape(0, 50, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> EncounterRequest.validateShape(EncounterRequest.MAX_PLAYERS + 1, 50, OptionalLong.empty()));
        assertDoesNotThrow(() -> EncounterRequest.validateShape(1, 50, OptionalLong.empty()));
        assertDoesNotThrow(() -> EncounterRequest.validateShape(EncounterRequest.MAX_PLAYERS, 50, OptionalLong.empty()));
    }

    @Test
    @DisplayName("the boss level is at least 1")
    void bossLevel() {
        assertThrows(IllegalArgumentException.class, () -> EncounterRequest.validateShape(1, 0, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class, () -> EncounterRequest.validateShape(1, -5, OptionalLong.empty()));
        assertDoesNotThrow(() -> EncounterRequest.validateShape(1, 1, OptionalLong.empty()));
    }

    @Test
    @DisplayName("a given health pool must be positive; leaving it out is fine")
    void maxHealth() {
        assertThrows(IllegalArgumentException.class, () -> EncounterRequest.validateShape(1, 50, OptionalLong.of(0)));
        assertThrows(IllegalArgumentException.class, () -> EncounterRequest.validateShape(1, 50, OptionalLong.of(-1)));
        assertDoesNotThrow(() -> EncounterRequest.validateShape(1, 50, OptionalLong.of(1)));
        assertDoesNotThrow(() -> EncounterRequest.validateShape(1, 50, OptionalLong.empty()));
    }
}
