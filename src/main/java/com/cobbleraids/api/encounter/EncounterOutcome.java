package com.cobbleraids.api.encounter;

/** How an owned encounter ended. */
public enum EncounterOutcome {
    /** The shared health pool reached zero, or the boss fainted by other means. */
    VICTORY,
    /** Every participant was defeated or left, or the time limit ran out. */
    DEFEAT,
    /** Ended by {@link CobbleRaidsEncounters#abort} or by an operator, not by the battle. */
    ABORTED
}
