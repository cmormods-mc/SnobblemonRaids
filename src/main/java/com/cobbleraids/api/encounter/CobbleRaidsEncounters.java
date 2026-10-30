package com.cobbleraids.api.encounter;

import com.cobbleraids.encounter.EncounterService;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

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

    /**
     * Withdraws only this participant, keeping teammates' shared fight running. Idempotent for
     * an already inactive participant whose actor is still retained by this encounter.
     * The actor remains registered until the shared battle ends, preventing concurrent battles
     * from racing the original Pokemon's eventual health/PP carryover. Call on the server thread.
     * Returns false for an absent encounter, a foreign player or a different current battle.
     */
    public static boolean withdraw(UUID encounterId, ServerPlayer player) {
        return EncounterService.withdraw(encounterId, player);
    }

    /**
     * Whether this player's current battle is this owned encounter, with their actor inactive.
     * The actor fights on clones and receives no further shared battle updates. Owners may
     * restore collection placement while retaining the battle lock; a second battle remains
     * prohibited until this encounter ends. Call on the server thread.
     */
    public static boolean hasWithdrawnParticipant(UUID encounterId, ServerPlayer player) {
        return EncounterService.hasWithdrawnParticipant(encounterId, player);
    }

    /** True from a successful {@link #start} until the listener has been told the encounter ended. */
    public static boolean isActive(UUID encounterId) {
        return EncounterService.isActive(encounterId);
    }
}
