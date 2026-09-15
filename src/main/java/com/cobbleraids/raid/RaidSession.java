package com.cobbleraids.raid;

import com.cobbleraids.api.encounter.EncounterListener;
import com.cobbleraids.api.encounter.EncounterPolicy;
import com.cobbleraids.lifecycle.RaidOutcome;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-authoritative state for one cooperative raid.
 *
 * <p>Identity lives here -- the battle, the boss entity, the definition -- and everything that can
 * be reasoned about without a server lives in {@link RaidProgress}, which this delegates to. The
 * split is why the health pool, contribution crediting and terminal-state rules can be tested at
 * all: none of them needs a PokemonBattle, but they used to be locked behind one.
 */
public final class RaidSession {
    public enum Status { WAITING, ACTIVE, COMPLETED, FAILED, ABORTED }

    /**
     * Present when another mod owns this battle through CobbleRaidsEncounters; absent for every
     * ordinary raid. Its policy replaces the server config for exactly the side effects it names.
     */
    public record Ownership(UUID encounterId, ResourceLocation owner, EncounterPolicy policy, EncounterListener listener) {
        public Ownership {
            Objects.requireNonNull(encounterId, "encounterId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(listener, "listener");
        }
    }

    private final UUID id = UUID.randomUUID();
    private final PokemonBattle battle;
    private final PokemonEntity bossEntity;
    private final UUID bossActorId;
    private final ResourceLocation definitionId;
    private final boolean allowFlee;
    private final RaidProgress progress;
    private final String renownTitle;
    private final Ownership ownership;

    public RaidSession(PokemonBattle battle, Collection<ServerPlayer> players, float maxHealth,
                       PokemonEntity bossEntity, UUID bossActorId, ResourceLocation definitionId,
                       int timeLimitSeconds, boolean allowFlee, String renownTitle) {
        this(battle, players, maxHealth, bossEntity, bossActorId, definitionId, timeLimitSeconds, allowFlee,
                renownTitle, null);
    }

    public RaidSession(PokemonBattle battle, Collection<ServerPlayer> players, float maxHealth,
                       PokemonEntity bossEntity, UUID bossActorId, ResourceLocation definitionId,
                       int timeLimitSeconds, boolean allowFlee, String renownTitle, Ownership ownership) {
        this.renownTitle = renownTitle == null ? "" : renownTitle;
        this.battle = Objects.requireNonNull(battle, "battle");
        this.bossEntity = Objects.requireNonNull(bossEntity, "bossEntity");
        this.bossActorId = Objects.requireNonNull(bossActorId, "bossActorId");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.allowFlee = allowFlee;
        this.ownership = ownership;
        List<UUID> playerIds = new ArrayList<>(players.size());
        for (ServerPlayer player : players) playerIds.add(player.getUUID());
        this.progress = new RaidProgress(maxHealth, timeLimitSeconds, playerIds);
    }

    public UUID getId() { return id; }
    public PokemonBattle getBattle() { return battle; }
    public PokemonEntity getBossEntity() { return bossEntity; }
    public UUID getBossActorId() { return bossActorId; }
    public ResourceLocation getDefinitionId() { return definitionId; }
    public boolean isFleeAllowed() { return allowFlee; }
    /** "Kaelen, the Relentless", or empty. Captured at start: the boss entity is discarded on victory. */
    public String getRenownTitle() { return renownTitle; }
    /** Null for an ordinary raid. */
    public Ownership getOwnership() { return ownership; }
    public boolean isOwned() { return ownership != null; }

    public Set<UUID> getParticipants() { return progress.participants(); }
    public Set<UUID> getActiveParticipants() { return progress.activeParticipants(); }
    public boolean isActiveParticipant(UUID playerId) { return progress.isActiveParticipant(playerId); }
    public float getMaxHealth() { return progress.maxHealth(); }
    public float getCurrentHealth() { return progress.currentHealth(); }
    public int getTimeLimitTicks() { return progress.timeLimitTicks(); }
    public int getElapsedCombatTicks() { return progress.elapsedCombatTicks(); }
    public int getRemainingCombatTicks() { return progress.remainingCombatTicks(); }
    public boolean isTimed() { return progress.isTimed(); }
    public Status getStatus() { return progress.status(); }
    public RaidOutcome getOutcome() { return progress.outcome(); }
    public Map<UUID, Float> getContributionSnapshot() { return progress.contributionSnapshot(); }

    public void activate() { progress.activate(); }
    public boolean tickCombatTimer() { return progress.tickCombatTimer(); }
    public float damage(UUID contributor, float amount) { return progress.damage(contributor, amount); }
    public float heal(float amount) { return progress.heal(amount); }
    public boolean removeParticipant(UUID playerId) { return progress.removeParticipant(playerId); }
    public boolean failIfNoActiveParticipants() { return progress.failIfNoActiveParticipants(); }
    public boolean fail() { return progress.fail(); }
    public boolean completeViaRealFaint() { return progress.completeViaRealFaint(); }
    public boolean abort() { return progress.abort(); }
}
