package com.cobbleraids.battle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RaidSwitchRulesTest {

    @Test
    @DisplayName("a request with a forced-switch flag is a replacement for a fainted Pokemon")
    void forcedReplacementIsRecognised() {
        assertTrue(RaidSwitchRules.isForcedReplacement(List.of(true)));
        assertTrue(RaidSwitchRules.isForcedReplacement(List.of(false, true)));
        assertTrue(RaidSwitchRules.isForcedReplacement(Arrays.asList(null, true)));
    }

    @Test
    @DisplayName("an ordinary turn, an empty request or none at all is not a replacement")
    void ordinaryTurnIsNot() {
        assertFalse(RaidSwitchRules.isForcedReplacement(List.of(false)));
        assertFalse(RaidSwitchRules.isForcedReplacement(List.of()));
        assertFalse(RaidSwitchRules.isForcedReplacement(null));
    }

    @Test
    @DisplayName("no-switch refuses a chosen switch but never a replacement")
    void noSwitchStillLetsAFaintedPokemonBeReplaced() {
        assertTrue(RaidSwitchRules.refusesSwitch(false, false));
        assertFalse(RaidSwitchRules.refusesSwitch(false, true));
    }

    @Test
    @DisplayName("when switching is allowed nothing is refused")
    void switchingAllowedRefusesNothing() {
        assertFalse(RaidSwitchRules.refusesSwitch(true, false));
        assertFalse(RaidSwitchRules.refusesSwitch(true, true));
    }
}
