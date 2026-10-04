package com.cobbleraids.battle;

import java.util.List;

/**
 * When a "no switching" rule may refuse a switch.
 *
 * <p>An encounter owner can turn voluntary switching off ({@code EncounterRules.switchingAllowed}). That means a player
 * may not swap a healthy Pokemon out for another one. It has never meant that a player whose Pokemon has just fainted
 * may not send in a replacement: Showdown then asks for a <b>forced switch</b>, which arrives as the same
 * {@code SwitchActionResponse}, and refusing it leaves that player's battle waiting for a choice they are not allowed to
 * make. The rest of the team can finish the turn but the fight never moves again. Found when a three-player Tower boss
 * run under a no-switch rule stalled the moment one player's Pokemon fainted.
 *
 * <p>Pure, so the rule is unit-tested without a Cobblemon runtime.
 */
public final class RaidSwitchRules {

    private RaidSwitchRules() {}

    /**
     * Whether Showdown is asking this player to replace a fainted Pokemon: any active slot's {@code forceSwitch} flag is
     * set in the request the player is answering.
     */
    public static boolean isForcedReplacement(List<Boolean> forceSwitch) {
        return forceSwitch != null && forceSwitch.stream().anyMatch(Boolean.TRUE::equals);
    }

    /** True when a switch action must be refused: switching is off and this is a choice, not a replacement. */
    public static boolean refusesSwitch(boolean switchingAllowed, boolean forcedReplacement) {
        return !switchingAllowed && !forcedReplacement;
    }
}
