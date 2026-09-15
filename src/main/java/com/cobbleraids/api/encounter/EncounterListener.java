package com.cobbleraids.api.encounter;

import java.util.UUID;

/**
 * What an encounter's owner is told. Called on the server thread.
 *
 * <p>An exception thrown from here is contained and reported in the server log; it never stops the
 * encounter's own cleanup, and the boss is removed either way.
 */
public interface EncounterListener {

    /**
     * A participant left the battle before it ended. The battle continues for everyone else; if
     * nobody is left, {@link #onEnded} follows as a defeat.
     */
    default void onParticipantLeft(UUID encounterId, UUID playerId, LeaveReason reason) {}

    /** Called exactly once, after the battle has ended and the boss has been removed. */
    void onEnded(EncounterResult result);
}
