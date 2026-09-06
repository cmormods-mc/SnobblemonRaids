package com.cobbleraids.lifecycle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Single authority for raid terminal transitions, cleanup, withdrawal, and reward finalization. */
public final class RaidLifecycleCoordinator {
    private static final Set<UUID> FINALIZED = ConcurrentHashMap.newKeySet();
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

    public static void abort(RaidSession raid) {
        if (raid == null || !raid.abort()) return;
        finalizeNonVictory(raid);
    }

    private static void finalizeVictory(RaidSession raid) {
        if (!FINALIZED.add(raid.getId())) return;
        RaidCombatRuleService.forget(raid.getId());
        RaidRewardEligibility eligibility = RaidRewardEligibility.victory(raid);
        RaidRewardService.grant(eligibility, ((net.minecraft.server.level.ServerLevel) raid.getBossEntity().level()).getServer());
        RaidRegistry.remove(raid.getBattle());
        cleanupBossEntity(raid);
        forgetFinalizationState(raid.getId());
    }

    /** Used when Cobblemon/Showdown already ended the battle and then emitted BATTLE_VICTORY. */
    private static void finalizeAfterBattleEnded(RaidSession raid) {
        if (!FINALIZED.add(raid.getId())) return;
        RaidCombatRuleService.forget(raid.getId());
        RaidRegistry.remove(raid.getBattle());
        cleanupBossEntity(raid);
        forgetFinalizationState(raid.getId());
    }

    /** Used for timeout/flee/abort paths where no normal Showdown win packet is guaranteed. */
    private static void finalizeNonVictory(RaidSession raid) {
        if (!FINALIZED.add(raid.getId())) return;
        RaidCombatRuleService.forget(raid.getId());
        PokemonBattle battle = raid.getBattle();
        if (!battle.getEnded()) {
            // PokemonBattle.end() sends BattleEndPacket, lets entity-backed actors clear battleId,
            // and calls BattleRegistry.closeBattle(this). Do not close the registry first.
            battle.end();
        }
        RaidRegistry.remove(battle);
        cleanupBossEntity(raid);
        forgetFinalizationState(raid.getId());
    }

    private static void cleanupBossEntity(RaidSession raid) {
        var boss = raid.getBossEntity();
        if (boss != null && !boss.isRemoved()) boss.discard();
    }

    /**
     * FINALIZED/VICTORY_REQUESTED exist only to make re-entrant finalize- and requestVictory calls
     * idempotent while a raid is still reachable through RaidRegistry. RaidRegistry.remove(battle)
     * has already run by the time this is called, so the battle can no longer resolve back to this
     * raid and these guards can never be consulted for its id again -- keeping the entries forever
     * would just grow both sets by one UUID per raid for the life of the server.
     */
    private static void forgetFinalizationState(UUID raidId) {
        FINALIZED.remove(raidId);
        VICTORY_REQUESTED.remove(raidId);
    }
}
