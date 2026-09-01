package com.cobbleraids.mixin.battle;

import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.battles.InBattleGimmickMove;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.MoveTarget;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import com.cobblemon.mod.common.net.messages.server.battle.BattleSelectActionsPacket;
import com.cobblemon.mod.common.net.serverhandling.battle.BattleSelectActionsHandler;
import java.util.List;
import net.minecraft.class_124;
import net.minecraft.class_2561;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raid-specific action boundary fixes.
 *
 * 1) Cobblemon clients may omit targetPnx when the UI presents the raid boss as the only hostile
 *    choice even though the Java-side multi-actor geometry makes the move target list explicit.
 *    For raid moves only, a missing target is filled with the active raid boss iff Cobblemon's own
 *    target-list function says that boss is a legal target. Self/ally/field moves are untouched.
 *
 * 2) Explicit forfeits never reach stock PokemonBattle.checkForfeit(); raid withdrawal semantics
 *    are handled by RaidLifecycleCoordinator instead.
 */
@Mixin(BattleSelectActionsHandler.class)
public abstract class RaidBattleSelectActionsMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/server/battle/BattleSelectActionsPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cobbleRaids$prepareRaidActions(BattleSelectActionsPacket packet, MinecraftServer server,
                                                 class_3222 player, CallbackInfo ci) {
        PokemonBattle battle = BattleRegistry.getBattle(packet.getBattleId());
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;

        cobbleRaids$fillMissingBossTargets(packet, battle, raid, player);

        boolean requestedForfeit = packet.getShowdownActionResponses().stream()
                .anyMatch(response -> response instanceof ForfeitActionResponse);
        if (!requestedForfeit) return;

        ci.cancel();
        if (!raid.isActiveParticipant(player.method_5667())) return;

        if (!raid.isFleeAllowed()) {
            player.method_43496(class_2561.method_43470("Fleeing is disabled for this raid.").method_27692(class_124.field_1061));
            BattleActor actor = battle.getActor(player);
            if (actor != null && actor.getRequest() != null) {
                actor.sendUpdate(new BattleQueueRequestPacket(actor.getRequest()));
                actor.sendUpdate(new BattleMakeChoicePacket());
            }
            return;
        }

        RaidLifecycleCoordinator.withdrawPlayer(raid, player);
    }

    private static void cobbleRaids$fillMissingBossTargets(BattleSelectActionsPacket packet,
                                                            PokemonBattle battle,
                                                            RaidSession raid,
                                                            class_3222 player) {
        BattleActor playerActor = battle.getActor(player);
        BattleActor bossActor = battle.getActor(raid.getBossActorId());
        if (playerActor == null || bossActor == null) return;

        ActiveBattlePokemon boss = bossActor.getActivePokemon().stream()
                .filter(pokemon -> pokemon != null && !pokemon.isGone())
                .findFirst()
                .orElse(null);
        if (boss == null) return;

        ShowdownActionRequest request = playerActor.getRequest();
        List<ShowdownMoveset> movesets = request == null ? null : request.getActive();
        if (movesets == null) return;

        List<ShowdownActionResponse> responses = packet.getShowdownActionResponses();
        List<ActiveBattlePokemon> activePokemon = playerActor.getActivePokemon();
        int count = Math.min(responses.size(), Math.min(activePokemon.size(), movesets.size()));

        for (int i = 0; i < count; i++) {
            ShowdownActionResponse raw = responses.get(i);
            if (!(raw instanceof MoveActionResponse move) || move.getTargetPnx() != null) continue;

            ActiveBattlePokemon user = activePokemon.get(i);
            ShowdownMoveset moveset = movesets.get(i);
            if (user == null || moveset == null) continue;

            InBattleMove selected = moveset.getMoves().stream()
                    .filter(candidate -> candidate.getId().equals(move.getMoveName()))
                    .findFirst()
                    .orElse(null);
            if (selected == null) continue;

            MoveTarget targetType = selected.getTarget();
            InBattleGimmickMove gimmick = selected.getGimmickMove();
            if (move.getGimmickID() != null && gimmick != null && !gimmick.getDisabled()) {
                targetType = gimmick.getTarget();
            }
            if (targetType == null) continue;

            List<Targetable> legalTargets = targetType.getTargetList().invoke(user);
            if (legalTargets != null && legalTargets.contains(boss)) {
                move.setTargetPnx(boss.getPNX());
            }
        }
    }
}
