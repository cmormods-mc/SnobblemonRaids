package com.cobbleraids.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbleraids.api.encounter.EncounterOutcome;
import com.cobbleraids.lifecycle.RaidOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EncounterOutcomesTest {

    @Test
    @DisplayName("every raid outcome reaches the owner as the outcome of the same name")
    void everyOutcomeMaps() {
        for (RaidOutcome outcome : RaidOutcome.values()) {
            assertEquals(outcome.name(), EncounterOutcomes.of(outcome).name());
        }
        assertEquals(RaidOutcome.values().length, EncounterOutcome.values().length,
                "an owner would be missing, or be sent, an outcome the other side does not have");
    }

    @Test
    @DisplayName("an encounter that ended with no recorded outcome is reported as aborted")
    void missingOutcomeIsAnAbort() {
        assertEquals(EncounterOutcome.ABORTED, EncounterOutcomes.of(null));
    }
}
