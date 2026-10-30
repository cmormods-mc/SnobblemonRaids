package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon's normal PvE distance/entity flee scan is not raid recruitment/flee semantics.
 * Raid forfeits are handled explicitly at the action-response boundary instead.
 */
@Mixin(PokemonBattle.class)
public abstract class RaidPokemonBattleMixin {
    @Inject(method = "checkForInputDispatch", at = @At("HEAD"))
    private void cobbleRaids$clearInactiveRaidChoices(CallbackInfo ci) {
        try {
            PokemonBattle self = (PokemonBattle) (Object) this;
            RaidSession raid = RaidRegistry.get(self);
            if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
            // Cobblemon's turn() resets mustChoose for every actor, including one whose Showdown
            // side is now inert after >raidleave. No client request will ever arrive for that actor.
            for (var actor : self.getActors()) {
                if (!(actor instanceof PlayerBattleActor) || raid.isActiveParticipant(actor.getUuid())) continue;
                actor.setMustChoose(false);
                actor.setRequest(null);
                actor.getResponses().clear();
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:inactiveRaidChoices", ex);
        }
    }

    @Inject(method = "checkFlee", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$disableNativeRaidDistanceFlee(CallbackInfo ci) {
        try {
            PokemonBattle self = (PokemonBattle) (Object) this;
            if (RaidRegistry.contains(self)) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:checkFlee", ex);
        }
    }
}
