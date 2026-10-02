package com.cobbleraids.mixin.battle;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonServerDelegate;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a raid boss's movement lock back after Cobblemon resets the entity's attributes.
 *
 * <p>PokemonServerDelegate#updateAttributes begins with removeAllEffects(), which takes the
 * infinite Slowness VII RaidBossSpawner applies with it. Through Cobblemon 1.7.3 that ran only from
 * changePokemon, before the spawner's own call. From 1.8 PokemonServerDelegate#tick also runs it
 * whenever the Pokemon's level differs from the acknowledged one, which is true on every new
 * entity's first tick and again whenever raid scaling changes the level. On 1.8.1 the boss lost its
 * slowness within about six ticks of spawning and walked off its announced coordinates, so players
 * recruited next to it fell outside the recruitment radius and the raid was cancelled at lock.
 *
 * <p>The glow is not handled here: RaidBossGlowService re-applies it on its own timer.
 */
@Mixin(PokemonServerDelegate.class)
public abstract class RaidBossAttributeResetMixin {
    @Inject(method = "updateAttributes", at = @At("TAIL"))
    private void cobbleRaids$restoreMovementLock(Pokemon pokemon, CallbackInfo ci) {
        // Runs on a raid boss's first tick and on every level change; a throw would unwind into
        // Cobblemon's entity tick. Failing open leaves the boss free to wander, which is the
        // behaviour this exists to prevent but not worth breaking the tick over.
        try {
            PokemonEntity boss = ((PokemonServerDelegate) (Object) this).getEntity();
            if (boss != null && RaidBossEntityMarker.isRaidBoss(boss)) RaidBossSpawner.applyMovementLock(boss);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:updateAttributes", ex);
        }
    }
}
