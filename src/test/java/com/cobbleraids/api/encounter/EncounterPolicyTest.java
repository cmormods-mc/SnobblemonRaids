package com.cobbleraids.api.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An owned encounter has no side effects unless its owner asks. The dangerous one is the catch: it
 * copies the boss into a player's storage, so it must never be on by accident.
 */
class EncounterPolicyTest {

    private static List<Boolean> flags(EncounterPolicy policy) {
        return List.of(policy.catchable(), policy.raidRewards(), policy.raidHistory(),
                policy.progression(), policy.carryHealth(), policy.carryPp());
    }

    @Test
    @DisplayName("none() turns every side effect off, catching included")
    void noneIsNone() {
        EncounterPolicy none = EncounterPolicy.none();
        assertFalse(none.catchable(), "a boss must never be catchable unless the owner says so");
        assertEquals(List.of(false, false, false, false, false, false), flags(none));
    }

    @Test
    @DisplayName("each wither turns on exactly its own side effect")
    void withersTouchOnlyTheirOwnFlag() {
        List<Function<EncounterPolicy, EncounterPolicy>> withers = List.of(
                policy -> policy.withCatchable(true),
                policy -> policy.withRaidRewards(true),
                policy -> policy.withRaidHistory(true),
                policy -> policy.withProgression(true),
                policy -> policy.withCarryover(true, false),
                policy -> policy.withCarryover(false, true));
        for (int i = 0; i < withers.size(); i++) {
            List<Boolean> after = flags(withers.get(i).apply(EncounterPolicy.none()));
            for (int flag = 0; flag < after.size(); flag++) {
                assertEquals(flag == i, after.get(flag), "wither " + i + " changed flag " + flag);
            }
        }
    }
}
