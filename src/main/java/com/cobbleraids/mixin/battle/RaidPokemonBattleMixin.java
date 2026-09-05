package com.cobbleraids.mixin.battle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
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
    @Inject(method = "checkFlee", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$disableNativeRaidDistanceFlee(CallbackInfo ci) {
        PokemonBattle self = (PokemonBattle) (Object) this;
        if (RaidRegistry.contains(self)) ci.cancel();
    }
}
