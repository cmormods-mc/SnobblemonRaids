package com.cobbleraids.lifecycle;

import com.cobbleraids.catching.RaidCatchService;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
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
            // >raidwin is raid-only Showdown INPUT. Our patch converts it to ordinary
            // |win|UUID&UUID... OUTPUT, which Cobblemon's stock WinInstruction handles.
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

        // Raid Showdown considers all player sides one cooperative team. The boss may win only
        // after every non-withdrawn player side is exhausted.
        boolean bossWon = event.getWinners().stream().anyMatch(actor -> raid.getBossActorId().equals(actor.getUuid()));
        if (bossWon) {
            if (raid.fail()) finalizeAfterBattleEnded(raid);
            return;
        }

        if (!event.getWinners().isEmpty()) {
            // The boss's real Pokemon fainted through a mechanism outside the -raiddamage pool
            // (Perish Song, Destiny Bond, the Perish Body ability, ...) before the pool reached
            // zero. Cobblemon already declared the players the winners, so honor it as a genuine
            // victory -- otherwise the raid never finalizes, RaidRewardService never grants a
            // reward, and any other mod's own "wild Pokemon fainted" hook fires in its place.
            if (raid.completeViaRealFaint()) finalizeVictory(raid);
        } else {
            // Empty winners is a mutual whiteout -- e.g. Perish Song/Perish Body fainting everyone
            // on the same turn. Nobody defeated the boss, but the battle has genuinely ended, so
            // finalize as a loss rather than leaving the session (and the battle UI) hanging.
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
     * Explicit player withdrawal. The Showdown side becomes inert without zeroing the player's
     * actual BattlePokemon HP; this preserves the legitimate battle state accumulated before leaving.
     *
     * Cobblemon's battle registry still owns the actor until the shared battle ends. This is deliberate:
     * allowing that same party to enter a second battle concurrently would race its BattlePokemon state.
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
        // Close this player's battle UI immediately. Future actor updates are suppressed by the
        // PlayerBattleActor mixin while the shared battle safely retains the actor until final cleanup.
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

    /** Disconnects cannot be rejected, so they are treated as a reward-forfeiting withdrawal. */
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
            if (player != null) player.sendSystemMessage(
                    Component.literal("Time expired. The raid was lost.").withStyle(ChatFormatting.RED));
        }
        finalizeNonVictory(raid);
    }

    public static void onBattleFainted(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        // A single faint is intentionally non-terminal. Showdown requests replacement normally.
    }

    /**
     * Administrative cancellation, not a defeat. It must not record a failed attempt against the
     * boss, and it must always remove it: RaidAdminBossOps.safelyRemove delegates the whole despawn
     * to this for a boss that is mid-battle, so leaving the boss standing here would make
     * /cobbleraids despawn silently do nothing to exactly the bosses an operator most wants gone.
     */
    public static void abort(RaidSession raid) {
        if (raid == null || !raid.abort()) return;
        finalizeNonVictory(raid, false);
    }

    private static void finalizeVictory(RaidSession raid) {
        RaidCombatRuleService.forget(raid.getId());
        MinecraftServer server = ((net.minecraft.server.level.ServerLevel) raid.getBossEntity().level()).getServer();
        FINALIZATION.finalizeOnce(raid.getId(), () -> {
            // Before the reward screen is queued, so a level-up and its evolution offer reach the
            // chat ahead of the screen rather than arriving behind it. Victory paths only: a lost,
            // timed-out or aborted raid pays nothing, exactly as it pays no items.
            RaidProgressionTransfer.grant(raid, server);
            RaidBattleStateCarryover.apply(raid);
            // Both need the boss's Pokemon, which the cleanup below is about to discard, and both
            // read the same per-player history, so they run together and before it.
            recordAndOfferCatch(raid, server);
            RaidRewardService.grant(RaidRewardEligibility.victory(raid), server);
        }, () -> {
            RaidRegistry.remove(raid.getBattle());
            cleanupBossEntity(raid);
            VICTORY_REQUESTED.remove(raid.getId());
        });
    }

    /**
     * Credits every victor's raid history, then offers each of them a chance at the boss.
     *
     * <p>History is recorded for everyone who stayed, whether or not catching is switched on: the
     * record is the substrate a catch mechanic is chosen from later, so it has to have been
     * accumulating before the choice is made, or the feature launches with every player at zero.
     */
    private static void recordAndOfferCatch(RaidSession raid, MinecraftServer server) {
        RaidDefinition definition = RaidDefinitionRegistry.get(raid.getDefinitionId());
        if (definition == null) return;
        var boss = raid.getBossEntity();
        Pokemon bossPokemon = boss == null ? null : boss.getPokemon();
        Set<UUID> victors = raid.getActiveParticipants();
        int participants = victors.size();
        // Same figures the reward screen shows, computed once rather than per player.
        Map<UUID, Double> contributions =
                ContributionMath.percentages(raid.getContributionSnapshot(), victors);

        for (UUID playerId : victors) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            double contribution = contributions.getOrDefault(playerId, 0.0);
            RaidPlayerRecords.recordWin(server, playerId, definition.rarityTier(),
                    definition.id(), contribution);
            if (player != null) {
                RaidCatchService.tryCatch(player, definition, bossPokemon, contribution, participants);
            }
        }
    }

    /** Used when Cobblemon/Showdown already ended the battle and then emitted BATTLE_VICTORY. */
    private static void finalizeAfterBattleEnded(RaidSession raid) {
        RaidCombatRuleService.forget(raid.getId());
        FINALIZATION.finalizeOnce(raid.getId(),
                () -> RaidBattleStateCarryover.apply(raid),
                () -> {
                    RaidRegistry.remove(raid.getBattle());
                    releaseBossAfterFailure(raid);
                    VICTORY_REQUESTED.remove(raid.getId());
                });
    }

    /** Every ordinary way a raid is lost: the players failed, so the boss records the attempt. */
    private static void finalizeNonVictory(RaidSession raid) {
        finalizeNonVictory(raid, true);
    }

    /** Used for timeout/flee/abort paths where no normal Showdown win packet is guaranteed. */
    private static void finalizeNonVictory(RaidSession raid, boolean countsAsFailedAttempt) {
        RaidCombatRuleService.forget(raid.getId());
        PokemonBattle battle = raid.getBattle();
        FINALIZATION.finalizeOnce(raid.getId(),
                // Read the clones before end(), which retires the actors this walks.
                () -> RaidBattleStateCarryover.apply(raid),
                () -> {
                    // Ending the battle is cleanup, not a side effect: skipping it strands
                    // Cobblemon's actors and leaves every player sitting in a battle UI they cannot
                    // leave. PokemonBattle.end() sends BattleEndPacket, lets entity-backed actors
                    // clear battleId, and calls BattleRegistry.closeBattle(this) -- so it must run
                    // before RaidRegistry.remove, and the registry must not be closed first.
                    if (!battle.getEnded()) battle.end();
                    RaidRegistry.remove(battle);
                    if (countsAsFailedAttempt) releaseBossAfterFailure(raid); else cleanupBossEntity(raid);
                    VICTORY_REQUESTED.remove(raid.getId());
                });
    }

    /**
     * Every terminal path ends here, so this is the single place that hands a natural boss's raid
     * slot back to the scheduler. RaidSpawnScheduler.onEntityUnloaded would also catch the discard
     * below, but only while the boss is in a loaded chunk; forgetting it explicitly makes the
     * release a property of the raid ending rather than of Minecraft's entity-removal plumbing, and
     * covers the case where the entity is already gone and discard() is skipped. Forgetting a UUID
     * the scheduler never tracked (an admin-spawned boss) is a no-op.
     */
    /**
     * A raid that ended in defeat leaves the boss standing for another attempt, until it has beaten
     * enough parties. Before this, every terminal path discarded the boss, so any loss consumed it
     * and there was effectively one attempt per boss no matter what.
     *
     * <p>The count lives on the entity ({@link RaidBossEntityMarker#recordFailedAttempt}), so it
     * has the boss's own lifetime and needs no cleanup anywhere. A surviving boss is deliberately
     * NOT forgotten by the scheduler: it still holds its raid slot, and its unattended-despawn and
     * total-lifetime timers keep running, so a boss nobody can beat still leaves on schedule.
     *
     * <p>The boss is fully healed between attempts. Its Pokemon is the real entity's, not a clone,
     * so the damage from the failed raid is really on it, and a second party would otherwise walk
     * into a boss at whatever HP the last one left -- while the raid's own health pool restarts at
     * full, making the bar disagree with the entity.
     */
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

    /** Everyone still in the battle, including players who withdrew, since they were all there. */
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

    /**
     * Both guards are released per raid inside the must-run cleanup of every terminal path, so
     * neither grows with the number of raids played. This covers the remaining case: a server that
     * stops with raids still in flight never reaches a terminal path at all, and an integrated
     * (single-player) client reuses this JVM for every world it opens.
     */
    public static void onServerStopped() {
        FINALIZATION.clear();
        VICTORY_REQUESTED.clear();
    }
}
