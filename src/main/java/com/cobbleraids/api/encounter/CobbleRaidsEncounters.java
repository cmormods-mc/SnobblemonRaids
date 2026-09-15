package com.cobbleraids.api.encounter;

import com.cobbleraids.encounter.EncounterService;
import java.util.UUID;

/**
 * Starts CobbleRaids boss battles that another mod owns.
 *
 * <p>An owned encounter is a real raid battle -- shared health pool, raid boss AI, the same Showdown
 * integration -- whose side effects belong to the caller. Catching, raid rewards, raid history,
 * progression and battle-state carryover each happen only if {@link EncounterPolicy} asks for them,
 * the boss cannot be recruited by passers-by, and it is removed when the encounter ends however it
 * ends. What happened is reported to the {@link EncounterListener}.
 *
 * <p>Everything in this package is the whole contract. Signatures use only {@code java.*},
 * {@code net.minecraft.*} and this package, which a build-time check enforces; nothing from
 * CobbleRaids' own packages or from Cobblemon is part of it.
 *
 * <p><b>Experimental.</b> {@link #API_VERSION} 1 is shaped by its first consumer, CobbleTowers, and
 * may change before that project's battle phase is finished.
 *
 * <p>{@link #start} and {@link #abort} must be called on the server thread and throw
 * {@link IllegalStateException} otherwise.
 */
public final class CobbleRaidsEncounters {

    /** Incremented on any incompatible change to this package. */
    public static final int API_VERSION = 1;

    private CobbleRaidsEncounters() {}

    /**
     * Spawns the boss described by {@code request} and starts the battle.
     *
     * <p>A refusal leaves nothing behind: no boss entity, no session, no listener call.
     */
    public static StartResult start(EncounterRequest request, EncounterListener listener) {
        return EncounterService.start(request, listener);
    }

    /**
     * Ends an active encounter as {@link EncounterOutcome#ABORTED}: the battle is closed, the boss is
     * removed and the listener hears {@code onEnded}. Returns false if no such encounter is active.
     */
    public static boolean abort(UUID encounterId) {
        return EncounterService.abort(encounterId);
    }

    /** True from a successful {@link #start} until the listener has been told the encounter ended. */
    public static boolean isActive(UUID encounterId) {
        return EncounterService.isActive(encounterId);
    }
}
