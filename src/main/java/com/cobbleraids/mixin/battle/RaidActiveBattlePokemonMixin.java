package com.cobbleraids.mixin.battle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.Targetable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cobblemon's stock opposite-slot lookup assumes a conventional mirrored field.
 * Raid battles place several allied player actors opposite one boss, so there may
 * be no geometrically mirrored slot. For raid-only positioning, resolve any live
 * non-allied active Pokemon instead: players resolve the boss; the boss resolves
 * one active player. Normal battles retain Cobblemon's stock behavior.
 */
@Mixin(ActiveBattlePokemon.class)
public abstract class RaidActiveBattlePokemonMixin {
    @Inject(method = "getOppositeOpponent", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidOppositeOpponent(CallbackInfoReturnable<Targetable> cir) {
        ActiveBattlePokemon self = (ActiveBattlePokemon) (Object) this;
        if (!RaidRegistry.contains(self.getBattle())) return;

        for (ActiveBattlePokemon candidate : self.getAllActivePokemon()) {
            if (candidate == self || !candidate.hasPokemon() || candidate.isGone()) continue;
            if (!self.isAllied(candidate)) {
                cir.setReturnValue(candidate);
                return;
            }
        }
    }
}
