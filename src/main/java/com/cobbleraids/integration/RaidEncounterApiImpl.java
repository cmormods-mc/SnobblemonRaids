package com.cobbleraids.integration;

import com.cobbleraids.api.RaidBossDescriptor;
import com.cobbleraids.api.RaidEncounterApi;
import com.cobbleraids.api.RaidEncounterHandle;
import com.cobbleraids.api.RaidEncounterResult;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.fault.RaidThreadGuard;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.ExternalEncounterCallbacks;
import com.cobbleraids.raid.RaidCompletionPolicy;
import com.cobbleraids.raid.RaidFactory;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Internal implementation of the stable addon-facing encounter API. */
public final class RaidEncounterApiImpl implements RaidEncounterApi {

    @Override
    public List<RaidBossDescriptor> bosses() {
        return RaidDefinitionRegistry.all().stream()
                .map(RaidEncounterApiImpl::descriptor)
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
    }

    @Override
    public Optional<RaidBossDescriptor> boss(ResourceLocation definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        RaidDefinition definition = RaidDefinitionRegistry.get(definitionId);
        return definition == null ? Optional.empty() : Optional.of(descriptor(definition));
    }

    @Override
    public RaidEncounterHandle start(
            ServerLevel level,
            Vec3 bossPosition,
            Collection<ServerPlayer> participants,
            ResourceLocation definitionId,
            double healthMultiplier,
            Consumer<RaidEncounterResult> completion
    ) {
        requireServerThread("api:start-external-encounter");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(bossPosition, "bossPosition");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(completion, "completion");
        if (!(healthMultiplier > 0.0) || !Double.isFinite(healthMultiplier)) {
            throw new IllegalArgumentException("healthMultiplier must be finite and > 0");
        }

        RaidDefinition definition = RaidDefinitionRegistry.get(definitionId);
        if (definition == null) throw new IllegalArgumentException("Unknown raid definition: " + definitionId);

        List<ServerPlayer> players = List.copyOf(participants);
        if (players.isEmpty()) throw new IllegalArgumentException("Encounter requires at least one participant");
        if (players.size() > definition.recruitment().maxPlayers()) {
            throw new IllegalArgumentException("Participant snapshot exceeds raid capacity");
        }
        for (ServerPlayer player : players) {
            if (player.level() != level) throw new IllegalStateException("All participants must be in the encounter level");
        }

        long maxHealth = multiplyHealth(definition.scaledHealth(players.size()), healthMultiplier);
        PokemonEntity bossEntity = RaidBossSpawner.spawnAt(level, bossPosition, definition);
        RaidSession session = null;
        try {
            session = RaidFactory.startFromWildBoss(
                    players,
                    definition,
                    bossEntity,
                    maxHealth,
                    RaidCompletionPolicy.EXTERNAL
            );
            ExternalEncounterCallbacks.bind(session, completion);
            return new RaidEncounterHandle(
                    session.getId(),
                    session.getBattle().getBattleId(),
                    session.getDefinitionId()
            );
        } catch (RuntimeException ex) {
            if (session != null) RaidLifecycleCoordinator.abort(session);
            else if (!bossEntity.isRemoved()) bossEntity.discard();
            throw ex;
        }
    }

    @Override
    public boolean withdraw(RaidEncounterHandle handle, ServerPlayer player) {
        requireServerThread("api:withdraw-external-encounter");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(player, "player");
        RaidSession raid = resolve(handle);
        return raid != null && RaidLifecycleCoordinator.withdrawPlayer(raid, player);
    }

    @Override
    public boolean abort(RaidEncounterHandle handle) {
        requireServerThread("api:abort-external-encounter");
        Objects.requireNonNull(handle, "handle");
        RaidSession raid = resolve(handle);
        if (raid == null) return false;
        RaidLifecycleCoordinator.abort(raid);
        return true;
    }

    private static void requireServerThread(String context) {
        if (!RaidThreadGuard.expectServerThread(context)) {
            throw new IllegalStateException("CobbleRaids encounter API must be called on the Minecraft server thread");
        }
    }

    private static RaidSession resolve(RaidEncounterHandle handle) {
        RaidSession raid = RaidRegistry.get(handle.encounterId());
        if (raid == null || !raid.isExternalEncounter()) return null;
        if (!raid.getBattle().getBattleId().equals(handle.battleId())) return null;
        if (!raid.getDefinitionId().equals(handle.definitionId())) return null;
        return raid;
    }

    private static RaidBossDescriptor descriptor(RaidDefinition definition) {
        return new RaidBossDescriptor(
                definition.id(),
                definition.species(),
                definition.rarityTier().serializedName(),
                definition.level(),
                definition.baseHealth(),
                definition.recruitment().maxPlayers(),
                definition.timeLimitSeconds(),
                definition.allowFlee()
        );
    }

    private static long multiplyHealth(long scaledHealth, double multiplier) {
        double value = Math.ceil(scaledHealth * multiplier);
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) value);
    }
}
