package com.cobbleraids.raid;

import com.cobbleraids.lifecycle.RaidCombatClock;
import com.cobbleraids.lifecycle.RaidOutcome;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a raid is going: the shared health pool, who is still in it, who did what damage, and which
 * terminal state it has reached.
 *
 * <p>Split out of {@link RaidSession}, which owns the other half -- identity. A session is a
 * PokemonBattle, a boss entity and a definition id, none of which can exist outside a running
 * server; progress is arithmetic and a state machine, and needs nothing from Minecraft at all. They
 * were the same class, so the arithmetic could only be exercised by starting a server and hitting
 * something, and it never was.
 *
 * <p>Two rules here have cost real debugging and are worth stating plainly. Contribution is credited
 * only to <em>active</em> participants, so a player who withdraws stops earning but keeps what they
 * earned. And every terminal transition is one-way and guarded on {@code ACTIVE}, because more than
 * one path can try to end the same raid -- a pool reaching zero, Cobblemon reporting a real faint,
 * the combat timer expiring, the last player leaving -- and whichever arrives first must win.
 *
 * <p>Mutating methods are synchronised, matching the session they were extracted from: damage and
 * heal arrive from Showdown instruction handling, participant changes from battle events.
 */
public final class RaidProgress {

    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeParticipants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Float> contribution = new ConcurrentHashMap<>();
    private final float maxHealth;
    private final RaidCombatClock combatClock;

    private volatile float currentHealth;
    private volatile RaidSession.Status status = RaidSession.Status.WAITING;
    private volatile RaidOutcome outcome;

    public RaidProgress(float maxHealth, int timeLimitSeconds, Iterable<UUID> players) {
        this.maxHealth = Math.max(1f, maxHealth);
        this.currentHealth = this.maxHealth;
        this.combatClock = new RaidCombatClock(timeLimitSeconds);
        for (UUID player : players) {
            participants.add(player);
            activeParticipants.add(player);
        }
    }

    public float maxHealth() { return maxHealth; }
    public float currentHealth() { return currentHealth; }
    public RaidSession.Status status() { return status; }
    public RaidOutcome outcome() { return outcome; }

    public int timeLimitTicks() { return combatClock.getLimitTicks(); }
    public int elapsedCombatTicks() { return combatClock.getElapsedTicks(); }
    public int remainingCombatTicks() { return combatClock.getRemainingTicks(); }
    public boolean isTimed() { return combatClock.isTimed(); }

    /** Every player who ever joined, including those who have since left. */
    public Set<UUID> participants() { return Collections.unmodifiableSet(new LinkedHashSet<>(participants)); }

    /** Players still in the fight; the set rewards are shared between. */
    public Set<UUID> activeParticipants() { return Collections.unmodifiableSet(new LinkedHashSet<>(activeParticipants)); }

    public boolean isActiveParticipant(UUID playerId) {
        return playerId != null && activeParticipants.contains(playerId);
    }

    public Map<UUID, Float> contributionSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(contribution));
    }

    public synchronized void activate() {
        if (status == RaidSession.Status.WAITING) status = RaidSession.Status.ACTIVE;
    }

    /** Advances only ACTIVE raid combat. Returns true exactly on the tick the time limit expires. */
    public synchronized boolean tickCombatTimer() {
        return status == RaidSession.Status.ACTIVE && combatClock.tick();
    }

    /**
     * Applies damage to the shared pool and credits it. Returns how much was actually applied, which
     * is clamped to the health left -- overkill must not inflate a contribution share.
     */
    public synchronized float damage(UUID contributor, float amount) {
        if (status != RaidSession.Status.ACTIVE || amount <= 0) return 0;
        float applied = Math.min(amount, currentHealth);
        currentHealth -= applied;
        if (contributor != null && activeParticipants.contains(contributor)) {
            contribution.merge(contributor, applied, Float::sum);
        }
        if (currentHealth <= 0.0001f) {
            currentHealth = 0;
            status = RaidSession.Status.COMPLETED;
            outcome = RaidOutcome.VICTORY;
        }
        return applied;
    }

    public synchronized float heal(float amount) {
        if (status != RaidSession.Status.ACTIVE || amount <= 0) return 0;
        float applied = Math.min(amount, maxHealth - currentHealth);
        currentHealth += applied;
        return applied;
    }

    /** Drops a player from the active set. They keep the contribution they already earned. */
    public synchronized boolean removeParticipant(UUID playerId) {
        if (playerId == null) return false;
        return activeParticipants.remove(playerId);
    }

    public synchronized boolean failIfNoActiveParticipants() {
        if (status != RaidSession.Status.ACTIVE || !activeParticipants.isEmpty()) return false;
        status = RaidSession.Status.FAILED;
        outcome = RaidOutcome.DEFEAT;
        return true;
    }

    public synchronized boolean fail() {
        if (status != RaidSession.Status.ACTIVE) return false;
        status = RaidSession.Status.FAILED;
        outcome = RaidOutcome.DEFEAT;
        return true;
    }

    /**
     * The boss's real Pokemon fainted through a mechanism outside the -raiddamage pool (Perish Song,
     * Destiny Bond, the Perish Body ability, ...), so the pool never reached zero on its own.
     * Cobblemon's own winner determination is authoritative for a genuine faint, so this closes out
     * the raid as a victory using whatever contribution has actually accumulated.
     */
    public synchronized boolean completeViaRealFaint() {
        if (status != RaidSession.Status.ACTIVE) return false;
        currentHealth = 0;
        status = RaidSession.Status.COMPLETED;
        outcome = RaidOutcome.VICTORY;
        return true;
    }

    public synchronized boolean abort() {
        if (status == RaidSession.Status.COMPLETED
                || status == RaidSession.Status.FAILED
                || status == RaidSession.Status.ABORTED) {
            return false;
        }
        status = RaidSession.Status.ABORTED;
        outcome = RaidOutcome.ABORTED;
        return true;
    }
}
