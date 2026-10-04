package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon's "a Pokemon was sent out" announcement throws when a participant is offline.
 *
 * <p>A raid keeps a disconnected player's place (the disconnect handler is deliberately cancelled so one player leaving
 * cannot end the fight for everyone). Their actor therefore stays in the battle with no entity behind it. Cobblemon 1.8.1's
 * {@code SwitchInstruction.broadcastSwitch} then tells every UUID in {@code battle.getPlayerUUIDs()} that the Pokemon was
 * seen, and building that {@code PokemonSeenEvent} for the absent player throws a NullPointerException from inside the
 * battle tick ("Exception while ticking a battle. Saving battle log."): the next switch by anyone, including a teammate
 * replacing a fainted Pokemon, takes the battle down with it.
 *
 * <p>The announcement is only chat lines and a Pokedex "seen" flag, so while anyone is offline it is skipped. The switch
 * itself is untouched (it happens in Showdown and in the instruction that called this). Fails open: if the check itself
 * throws, the stock method runs.
 */
@Mixin(targets = "com.cobblemon.mod.common.battles.interpreter.instructions.SwitchInstruction$Companion")
public abstract class SwitchBroadcastOfflinePlayerMixin {

    @Inject(method = "broadcastSwitch", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$skipWhileAParticipantIsOffline(PokemonBattle battle, BattleActor actor,
                                                           BattlePokemon newPokemon, BattlePokemon illusion,
                                                           CallbackInfo ci) {
        try {
            for (BattleActor each : battle.getActors()) {
                if (each instanceof PlayerBattleActor player && player.getEntity() == null) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:switchBroadcast", ex);
        }
    }
}
