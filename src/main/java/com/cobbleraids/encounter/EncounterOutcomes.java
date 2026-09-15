package com.cobbleraids.encounter;

import com.cobbleraids.api.encounter.EncounterOutcome;
import com.cobbleraids.lifecycle.RaidOutcome;

/** Translates the raid's internal outcome into the public one. Free of Minecraft types so it is testable. */
public final class EncounterOutcomes {

    private EncounterOutcomes() {}

    /**
     * An exhaustive switch, so adding a raid outcome fails compilation here rather than reaching an
     * owner as something it cannot handle. A session with no recorded outcome ended without the
     * battle deciding it, which is what an abort means to the owner.
     */
    public static EncounterOutcome of(RaidOutcome outcome) {
        if (outcome == null) return EncounterOutcome.ABORTED;
        return switch (outcome) {
            case VICTORY -> EncounterOutcome.VICTORY;
            case DEFEAT -> EncounterOutcome.DEFEAT;
            case ABORTED -> EncounterOutcome.ABORTED;
        };
    }
}
