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

    /**
     * The boss took damage from the shared pool: called as it happens, once per hit, on the server
     * thread. {@code applied} is what the pool actually lost (already clamped to what it had left),
     * never zero. Only damage with a known dealer is reported; weather, status and the boss hurting
     * itself have no player to credit. Does nothing unless an owner overrides it, and exceptions
     * are contained like every other callback.
     */
    default void onDamage(UUID encounterId, UUID playerId, float applied) {}

    /** Called exactly once, after the battle has ended and the boss has been removed. */
    void onEnded(EncounterResult result);
}
