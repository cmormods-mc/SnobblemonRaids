package com.cobbleraids.raid;

import com.cobbleraids.lifecycle.RaidOutcome;
import com.cobbleraids.lifecycle.RaidCombatClock;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Server-authoritative state for one cooperative raid. */
public final class RaidSession {
    public enum Status { WAITING, ACTIVE, COMPLETED, FAILED, ABORTED }

    private final UUID id = UUID.randomUUID();
    private final PokemonBattle battle;
    private final PokemonEntity bossEntity;
    private final UUID bossActorId;
    private final ResourceLocation definitionId;
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeParticipants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Float> contribution = new ConcurrentHashMap<>();
    private final float maxHealth;
    private final RaidCombatClock combatClock;
    private final boolean allowFlee;
    private volatile float currentHealth;
    private volatile Status status = Status.WAITING;
    private volatile RaidOutcome outcome;

    public RaidSession(PokemonBattle battle, Collection<ServerPlayer> players, float maxHealth,
                       PokemonEntity bossEntity, UUID bossActorId, ResourceLocation definitionId,
                       int timeLimitSeconds, boolean allowFlee) {
        this.battle = Objects.requireNonNull(battle, "battle");
        this.bossEntity = Objects.requireNonNull(bossEntity, "bossEntity");
        this.bossActorId = Objects.requireNonNull(bossActorId, "bossActorId");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.maxHealth = Math.max(1f, maxHealth);
        this.currentHealth = this.maxHealth;
        this.combatClock = new RaidCombatClock(timeLimitSeconds);
        this.allowFlee = allowFlee;
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            participants.add(id);
            activeParticipants.add(id);
        }
    }

    public UUID getId() { return id; }
    public PokemonBattle getBattle() { return battle; }
    public PokemonEntity getBossEntity() { return bossEntity; }
    public UUID getBossActorId() { return bossActorId; }
    public ResourceLocation getDefinitionId() { return definitionId; }
    public Set<UUID> getParticipants() { return Collections.unmodifiableSet(new LinkedHashSet<>(participants)); }
    public Set<UUID> getActiveParticipants() { return Collections.unmodifiableSet(new LinkedHashSet<>(activeParticipants)); }
    public boolean isActiveParticipant(UUID playerId) { return playerId != null && activeParticipants.contains(playerId); }
    public float getMaxHealth() { return maxHealth; }
    public float getCurrentHealth() { return currentHealth; }
    public int getTimeLimitTicks() { return combatClock.getLimitTicks(); }
    public int getElapsedCombatTicks() { return combatClock.getElapsedTicks(); }
    public int getRemainingCombatTicks() { return combatClock.getRemainingTicks(); }
    public boolean isTimed() { return combatClock.isTimed(); }
    public boolean isFleeAllowed() { return allowFlee; }
    public Status getStatus() { return status; }
    public RaidOutcome getOutcome() { return outcome; }

    public synchronized void activate() {
        if (status == Status.WAITING) status = Status.ACTIVE;
    }

    /** Advances only ACTIVE raid combat. Returns true exactly on the tick the time limit expires. */
    public synchronized boolean tickCombatTimer() {
        return status == Status.ACTIVE && combatClock.tick();
    }

    public synchronized float damage(UUID contributor, float amount) {
        if (status != Status.ACTIVE || amount <= 0) return 0;
        float applied = Math.min(amount, currentHealth);
        currentHealth -= applied;
        if (contributor != null && activeParticipants.contains(contributor)) contribution.merge(contributor, applied, Float::sum);
        if (currentHealth <= 0.0001f) {
            currentHealth = 0;
            status = Status.COMPLETED;
            outcome = RaidOutcome.VICTORY;
        }
        return applied;
    }

    public synchronized float heal(float amount) {
        if (status != Status.ACTIVE || amount <= 0) return 0;
        float applied = Math.min(amount, maxHealth - currentHealth);
        currentHealth += applied;
        return applied;
    }

    public synchronized boolean removeParticipant(UUID playerId) {
        if (playerId == null) return false;
        return activeParticipants.remove(playerId);
    }

    public synchronized boolean failIfNoActiveParticipants() {
        if (status != Status.ACTIVE || !activeParticipants.isEmpty()) return false;
        status = Status.FAILED;
        outcome = RaidOutcome.DEFEAT;
        return true;
    }

    public synchronized boolean fail() {
        if (status != Status.ACTIVE) return false;
        status = Status.FAILED;
        outcome = RaidOutcome.DEFEAT;
        return true;
    }

    /**
     * The boss's real Pokemon fainted through a mechanism outside the -raiddamage pool (Perish
     * Song, Destiny Bond, the Perish Body ability, ...), so the pool never reached zero on its own.
     * Cobblemon's own winner determination is authoritative for a genuine faint, so this closes out
     * the raid as a victory using whatever contribution has actually accumulated.
     */
    public synchronized boolean completeViaRealFaint() {
        if (status != Status.ACTIVE) return false;
        currentHealth = 0;
        status = Status.COMPLETED;
        outcome = RaidOutcome.VICTORY;
        return true;
    }

    public synchronized boolean abort() {
        if (status == Status.COMPLETED || status == Status.FAILED || status == Status.ABORTED) return false;
        status = Status.ABORTED;
        outcome = RaidOutcome.ABORTED;
        return true;
    }

    public Map<UUID, Float> getContributionSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(contribution));
    }
}
