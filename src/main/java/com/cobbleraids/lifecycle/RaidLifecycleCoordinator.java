package com.cobbleraids.lifecycle;

import com.cobbleraids.catching.RaidCatchService;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.fault.RaidThreadGuard;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Single authority for raid terminal transitions, cleanup, withdrawal, and reward finalization. */
public final class RaidLifecycleCoordinator {
    private static final RaidFinalizationGuard FINALIZATION = new RaidFinalizationGuard();
    private static final Set<UUID> VICTORY_REQUESTED = ConcurrentHashMap.newKeySet();

    private RaidLifecycleCoordinator() {}

    /** Called after RaidSession reaches COMPLETED. */
    public static void requestVictory(RaidSession raid) {
        if (raid == null || raid.getStatus() != RaidSession.Status.COMPLETED) return;
        if (!VICTORY_REQUESTED.add(raid.getId())) return;

        PokemonBattle battle = raid.getBattle();
        String winners = raid.getActiveParticipants().stream()
                .map(UUID::toString)
                .collect(Collectors.joining("&"));
        if (!winners.isEmpty() && !battle.getEnded()) {
            battle.writeShowdownAction(">raidwin " + winners);
        }
    }

    public static void onBattleVictory(com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent event) {
        PokemonBattle battle = event.getBattle();
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null) return;

        if (raid.getStatus() == RaidSession.Status.COMPLETED && raid.getOutcome() == RaidOutcome.VICTORY) {
            finalizeVictory(raid);
            return;
        }
        if (raid.getStatus() != RaidSession.Status.ACTIVE) return;

        boolean bossWon = event.getWinners().stream()
                .anyMatch(actor -> raid.getBossActorId().equals(actor.getUuid()));
        if (bossWon) {
            if (raid.fail()) finalizeAfterBattleEnded(raid);
            return;
        }

        if (!event.getWinners().isEmpty()) {
            if (raid.completeViaRealFaint()) finalizeVictory(raid);
        } else {
            if (raid.fail()) finalizeAfterBattleEnded(raid);
        }
    }

    /** Fallback for a Cobblemon flee event that escaped the explicit raid-forfeit interception. */
    public static void onPlayerFled(PokemonBattle battle, UUID playerId) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        raid.removeParticipant(playerId);
        if (raid.failIfNoActiveParticipants()) finalizeNonVictory(raid);
    }

    /**
     * Explicit player withdrawal. Cobblemon keeps the actor until the shared battle ends, while the
     * raid makes that side inert and closes only that player's battle UI.
     */
    public static boolean withdrawPlayer(RaidSession raid, ServerPlayer player) {
        if (raid == null || player == null || raid.getStatus() != RaidSession.Status.ACTIVE) return false;
        UUID playerId = player.getUUID();
        if (!raid.removeParticipant(playerId)) return false;

        PokemonBattle battle = raid.getBattle();
        BattleActor actor = battle.getActor(player);
        if (actor != null) {
            actor.getResponses().clear();
            actor.setRequest(null);
            actor.setMustChoose(false);
        }

        player.sendSystemMessage(Component.literal("You withdrew from the raid and forfeited its rewards.")
                .withStyle(ChatFormatting.YELLOW));
        new BattleEndPacket().sendToPlayer(player);

        if (raid.failIfNoActiveParticipants()) {
            finalizeNonVictory(raid);
            return true;
        }
        if (actor != null && actor.getShowdownId() != null && !battle.getEnded()) {
            battle.writeShowdownAction(">raidleave " + actor.getShowdownId());
        }
        return true;
    }

    /** Disconnects cannot be rejected, so they are treated as a withdrawal. */
    public static void onPlayerDisconnected(RaidSession raid, UUID playerId) {
        if (raid == null || playerId == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        if (!raid.removeParticipant(playerId)) return;

        PokemonBattle battle = raid.getBattle();
        BattleActor actor = battle.getActor(playerId);
        if (actor != null) {
            actor.getResponses().clear();
            actor.setRequest(null);
            actor.setMustChoose(false);
        }
        if (raid.failIfNoActiveParticipants()) {
            finalizeNonVictory(raid);
            return;
        }
        if (actor != null && actor.getShowdownId() != null && !battle.getEnded()) {
            battle.writeShowdownAction(">raidleave " + actor.getShowdownId());
        }
    }

    public static void timeout(RaidSession raid, MinecraftServer server) {
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE || !raid.fail()) return;
        for (UUID id : raid.getActiveParticipants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Time expired. The raid was lost.")
                        .withStyle(ChatFormatting.RED));
            }
        }
        finalizeNonVictory(raid);
    }

    public static void onBattleFainted(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        // A single faint is intentionally non-terminal. Showdown requests replacement normally.
    }

    /** Administrative cancellation; it never counts as a failed boss attempt. */
    public static void abort(RaidSession raid) {
        if (raid == null || !raid.abort()) return;
        finalizeNonVictory(raid, false);
    }

    private static void finalizeVictory(RaidSession raid) {
        RaidThreadGuard.expectServerThread("finalize-victory");
        RaidCombatRuleService.forget(raid.getId());
        MinecraftServer server = ((ServerLevel) raid.getBossEntity().level()).getServer();

        FINALIZATION.finalizeOnce(raid.getId(), () -> {
            if (raid.isExternalEncounter()) {
                // External encounters own rewards/progression. CobbleRaids still owns party-state
                // carryover because it owns the shared Cobblemon battle and its BattlePokemon clones.
                RaidBattleStateCarryover.apply(raid);
            } else {
                RaidProgressionTransfer.grant(raid, server);
                RaidBattleStateCarryover.apply(raid);
                recordAndOfferCatch(raid, server);
                RaidRewardService.grant(RaidRewardEligibility.victory(raid), server);
            }
        }, () -> {
            RaidRegistry.remove(raid.getBattle());
            cleanupBossEntity(raid);
            VICTORY_REQUESTED.remove(raid.getId());
        });
    }

    private static void recordAndOfferCatch(RaidSession raid, MinecraftServer server) {
        RaidDefinition definition = RaidDefinitionRegistry.get(raid.getDefinitionId());
        if (definition == null) return;

        var boss = raid.getBossEntity();
        Pokemon bossPokemon = boss == null ? null : boss.getPokemon();
        Set<UUID> victors = raid.getActiveParticipants();
        int participants = victors.size();
        Map<UUID, Double> contributions = ContributionMath.percentages(
                raid.getContributionSnapshot(), victors);

        Map<UUID, Double> earned = new LinkedHashMap<>();
        for (UUID playerId : victors) {
            earned.put(playerId, contributions.getOrDefault(playerId, 0.0));
        }
        RaidPlayerRecords.recordWins(server, definition.rarityTier(), definition.id(), earned);

        for (UUID playerId : victors) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            RaidCatchService.tryCatch(player, definition, bossPokemon,
                    contributions.getOrDefault(playerId, 0.0), participants);
        }
    }

    /** Used when Cobblemon/Showdown already ended the battle and then emitted BATTLE_VICTORY. */
    private static void finalizeAfterBattleEnded(RaidSession raid) {
        RaidCombatRuleService.forget(raid.getId());
        FINALIZATION.finalizeOnce(raid.getId(),
                () -> RaidBattleStateCarryover.apply(raid),
                () -> {
                    RaidRegistry.remove(raid.getBattle());
                    cleanupAfterFailure(raid, true);
                    VICTORY_REQUESTED.remove(raid.getId());
                });
    }

    /** Every ordinary way a raid is lost. */
    private static void finalizeNonVictory(RaidSession raid) {
        finalizeNonVictory(raid, true);
    }

    /** Used for timeout/flee/abort paths where no normal Showdown win packet is guaranteed. */
    private static void finalizeNonVictory(RaidSession raid, boolean countsAsFailedAttempt) {
        RaidCombatRuleService.forget(raid.getId());
        PokemonBattle battle = raid.getBattle();
        FINALIZATION.finalizeOnce(raid.getId(),
                () -> RaidBattleStateCarryover.apply(raid),
                () -> {
                    if (!battle.getEnded()) battle.end();
                    RaidRegistry.remove(battle);
                    cleanupAfterFailure(raid, countsAsFailedAttempt);
                    VICTORY_REQUESTED.remove(raid.getId());
                });
    }

    /**
     * External encounters are single-use by definition, so their boss is always discarded. Standard
     * raids retain the existing failed-attempt/retry behavior.
     */
    private static void cleanupAfterFailure(RaidSession raid, boolean countsAsFailedAttempt) {
        if (raid.isExternalEncounter() || !countsAsFailedAttempt) {
            cleanupBossEntity(raid);
        } else {
            releaseBossAfterFailure(raid);
        }
    }

    private static void releaseBossAfterFailure(RaidSession raid) {
        var boss = raid.getBossEntity();
        if (boss == null || boss.isRemoved()) {
            cleanupBossEntity(raid);
            return;
        }

        int attempts = RaidBossEntityMarker.recordFailedAttempt(boss);
        CobbleRaidsConfig.CombatDefaults combat = CobbleRaidsConfigManager.get().combatDefaults();
        boolean spent = combat.attemptsAreLimited() && attempts >= combat.maxFailedAttempts();

        MinecraftServer server = ((ServerLevel) boss.level()).getServer();
        if (spent) {
            announce(server, raid, Component.literal("The raid boss has driven off enough challengers and departs.")
                    .withStyle(ChatFormatting.RED));
            cleanupBossEntity(raid);
            return;
        }

        boss.getPokemon().heal();
        if (combat.attemptsAreLimited()) {
            int left = combat.maxFailedAttempts() - attempts;
            announce(server, raid, Component.literal("The raid boss remains. "
                    + left + " more failed attempt" + (left == 1 ? "" : "s") + " and it will depart.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void announce(MinecraftServer server, RaidSession raid, Component message) {
        if (server == null) return;
        for (UUID id : raid.getParticipants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(message);
        }
    }

    private static void cleanupBossEntity(RaidSession raid) {
        var boss = raid.getBossEntity();
        if (boss != null) RaidSpawnScheduler.forget(boss.getUUID());
        if (boss != null && !boss.isRemoved()) boss.discard();
    }

    public static int finalizationsInFlight() {
        return FINALIZATION.size();
    }

    public static void onServerStopped() {
        FINALIZATION.clear();
        VICTORY_REQUESTED.clear();
    }
}
