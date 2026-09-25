package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonServerDelegate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A fainted battle clone must not spill its species' death loot onto the ground.
 *
 * <p>RaidFactory sends a raid battle out on {@code toBattleTeam(clone = true)} clones -- see
 * {@link BattleClonePersistenceMixin}, which stops the same clones from being saved to disk. That
 * comment explains why a clone stands in the world as a real {@code PokemonEntity} at all: it is
 * what the player's Pokemon fights the boss as. {@code PokemonServerDelegate.doDeathDrops()} rolls
 * that entity's species drop table and removes it with {@code RemovalReason.KILLED} whenever its
 * post-death timer elapses, gated only on the entity having no vanilla owner recorded -- the check
 * that normally protects a player's own fainted Pokemon, since a real send-out ties the entity to
 * its owner. A battle clone is never sent out that way, so it fails that check and faints exactly
 * like an unowned wild Pokemon being defeated: held item and loot-table items drop, and the
 * fainted mon is gone from the world as "killed", even though it is only the disposable clone that
 * dies -- the real party member CobbleRaids drops here has already been carried back onto the
 * player's actual Pokemon by {@link RaidBattleStateCarryover}.
 *
 * <p>Cancelling here leaves the rest of {@code updatePostDeath()} untouched: the faint animation,
 * sound and eventual entity removal all still happen, so a fainted clone still looks and behaves
 * like one fainting -- it just does not pay out loot it was never meant to own.
 */
@Mixin(PokemonServerDelegate.class)
public abstract class BattleCloneNoDeathDropsMixin {
    @Inject(method = "doDeathDrops", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$noDropsForBattleClone(CallbackInfo ci) {
        try {
            PokemonServerDelegate self = (PokemonServerDelegate) (Object) this;
            PokemonEntity entity = self.getEntity();
            if (entity != null && entity.isBattleClone()) ci.cancel();
        } catch (Exception ex) {
            // Failing open lets a clone drop loot it shouldn't -- a gameplay blemish, not a stuck
            // raid; throwing here would unwind into Cobblemon's death-animation tick.
            RaidFaultBarrier.report("mixin:doDeathDrops", ex);
        }
    }
}
