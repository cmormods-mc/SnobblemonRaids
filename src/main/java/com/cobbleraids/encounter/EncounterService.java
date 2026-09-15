package com.cobbleraids.encounter;

import com.cobbleraids.RaidLog;
import com.cobbleraids.api.encounter.EncounterListener;
import com.cobbleraids.api.encounter.EncounterRequest;
import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbleraids.api.encounter.LeaveReason;
import com.cobbleraids.api.encounter.StartResult;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.raid.RaidFactory;
import com.cobbleraids.raid.RaidScalingPolicy;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.renown.RenownRequest;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/**
 * The implementation behind {@link com.cobbleraids.api.encounter.CobbleRaidsEncounters}.
 *
 * <p>An owned encounter is an ordinary raid session with an {@link RaidSession.Ownership} attached.
 * Everything that makes a raid work -- the shared pool, the boss AI, the Showdown patches, the
 * finalization guard -- is reused unchanged; the lifecycle coordinator consults the ownership's
 * policy at each side effect and calls back here at the points the owner is told about.
 */
public final class EncounterService {

    /** Active owned encounters by the caller's id. Removed when the listener is told the encounter ended. */
    private static final Map<UUID, RaidSession> ACTIVE = new ConcurrentHashMap<>();

    private EncounterService() {}

    public static StartResult start(EncounterRequest request, EncounterListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        requireServerThread(request.level().getServer());

        UUID encounterId = request.encounterId();
        if (ACTIVE.containsKey(encounterId)) return refused("encounter " + encounterId + " is already active");
        RaidDefinition definition = RaidDefinitionRegistry.get(request.definitionId());
        if (definition == null) return refused("no raid definition " + request.definitionId() + " is loaded");
        if (request.players().size() > definition.recruitment().maxPlayers()) {
            return refused(definition.id() + " allows at most " + definition.recruitment().maxPlayers()
                    + " players, not " + request.players().size());
        }
        for (var player : request.players()) {
            if (player.hasDisconnected()) return refused(player.getGameProfile().getName() + " is not online");
        }

        // Spawning also refuses when the Showdown integration is not installed, with its own message.
        PokemonEntity boss;
        try {
            boss = RaidBossSpawner.spawnAt(request.level(), request.position(), definition, RenownRequest.NONE);
        } catch (RuntimeException ex) {
            RaidLog.error("Owned encounter {} for {} could not spawn its boss", encounterId, request.owner(), ex);
            return refused("the boss could not be spawned: " + describe(ex));
        }

        try {
            // Before anything can fail and before the battle exists, so the boss is never recruitable
            // even for an instant.
            RaidBossEntityMarker.markOwned(boss, request.owner());
            applyLevel(boss, definition, request.bossLevel());
            long health = request.maxHealth().isPresent()
                    ? request.maxHealth().getAsLong()
                    : RaidScalingPolicy.maxHealth(definition, request.players().size(), request.bossLevel());
            RaidSession session = RaidFactory.startFromWildBoss(request.players(), definition, boss, health,
                    new RaidSession.Ownership(encounterId, request.owner(), request.policy(), listener));
            ACTIVE.put(encounterId, session);
            RaidLog.info("Owned encounter {} started for {}: {} at level {}, {} player(s), pool {}",
                    encounterId, request.owner(), definition.id(), request.bossLevel(),
                    request.players().size(), health);
            return new StartResult.Started(encounterId);
        } catch (RuntimeException ex) {
            // A refusal leaves nothing behind. The factory throws before it registers a session, so
            // the boss is the only thing that exists at this point.
            if (!boss.isRemoved()) boss.discard();
            return refused(describe(ex));
        }
    }

    public static boolean abort(UUID encounterId) {
        RaidSession session = encounterId == null ? null : ACTIVE.get(encounterId);
        if (session == null) return false;
        requireServerThread(session.getBossEntity().getServer());
        RaidLifecycleCoordinator.abort(session);
        return true;
    }

    public static boolean isActive(UUID encounterId) {
        return encounterId != null && ACTIVE.containsKey(encounterId);
    }

    /** Called by the lifecycle coordinator when a participant leaves an owned encounter. */
    public static void notifyLeft(RaidSession raid, UUID playerId, LeaveReason reason) {
        RaidSession.Ownership ownership = raid == null ? null : raid.getOwnership();
        if (ownership == null) return;
        RaidFaultBarrier.guard("encounter:listener-left", () ->
                ownership.listener().onParticipantLeft(ownership.encounterId(), playerId, reason));
    }

    /**
     * Called by the lifecycle coordinator as the last cleanup step of an owned encounter, after the
     * battle has ended and the boss has been removed.
     */
    public static void notifyEnded(RaidSession raid) {
        RaidSession.Ownership ownership = raid == null ? null : raid.getOwnership();
        if (ownership == null) return;
        ACTIVE.remove(ownership.encounterId(), raid);
        EncounterResult result = new EncounterResult(ownership.encounterId(),
                EncounterOutcomes.of(raid.getOutcome()), raid.getContributionSnapshot(),
                raid.getActiveParticipants(), raid.getElapsedCombatTicks());
        RaidLog.info("Owned encounter {} for {} ended: {}", ownership.encounterId(), ownership.owner(), result.outcome());
        RaidFaultBarrier.guard("encounter:listener-ended", () -> ownership.listener().onEnded(result));
    }

    /** Cleared with the other per-server statics; see CobbleRaids' SERVER_STOPPED block. */
    public static int onServerStopped() {
        int active = ACTIVE.size();
        ACTIVE.clear();
        return active;
    }

    /**
     * The caller's level, applied as given. Same ordering as the lobby's dynamic level: a level
     * change moves max HP, and a boss left on its old health would enter the fight already damaged.
     */
    private static void applyLevel(PokemonEntity boss, RaidDefinition definition, int level) {
        Pokemon pokemon = boss.getPokemon();
        if (pokemon.getLevel() != level) {
            pokemon.setLevel(level);
            pokemon.setCurrentHealth(pokemon.getMaxHealth());
        }
        boss.setCustomName(RaidBossNameplate.of(definition.rarityTier(),
                pokemon.getSpecies().getTranslatedName(), null, level == definition.level() ? 0 : level));
        boss.setCustomNameVisible(true);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("CobbleRaidsEncounters must be called on the server thread, not "
                    + Thread.currentThread().getName());
        }
    }

    private static StartResult refused(String reason) {
        return new StartResult.Refused(reason);
    }

    private static String describe(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
