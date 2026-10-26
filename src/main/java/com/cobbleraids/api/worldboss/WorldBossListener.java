package com.cobbleraids.api.worldboss;

import java.util.Map;
import java.util.UUID;

/**
 * What another mod hears about a World Boss. Called on the server thread.
 *
 * <p>An exception thrown from a listener is contained and logged; it never stops the other
 * listeners or the World Boss's own cleanup.
 */
public interface WorldBossListener {

    /**
     * The World Boss was defeated: its shared pool reached zero. Called once per World Boss in
     * normal running, and again after a restart only if the defeat was saved but the reward step
     * had not finished. An owner should therefore treat {@code bossId} as an idempotency key for
     * anything it pays.
     *
     * @param bossId       this World Boss's id; unique per World Boss, stable across restarts
     * @param definitionId the raid definition the boss was built from, e.g. {@code cobbleraids:darkrai}
     * @param damage       damage dealt to the pool per player, for every player who dealt any
     * @param totalDamage  the pool's size
     */
    void onDefeated(UUID bossId, String definitionId, Map<UUID, Float> damage, float totalDamage);

    /** The World Boss ended without being defeated (event window closed, or an operator stopped it). */
    default void onEnded(UUID bossId, String definitionId, Map<UUID, Float> damage, float totalDamage) {}
}
