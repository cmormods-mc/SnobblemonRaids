package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Never write a battle clone to disk. This is what duplicated players' Pokemon.
 *
 * <p>A raid battle does not fight with the player's actual party. RaidFactory calls
 * toBattleTeam(clone = true), so Cobblemon's BattlePokemon.safeCopyOf hands the battle a full
 * {@code clone()} of each Pokemon, tagged with BattleCloneProperty and made uncatchable, and it is
 * that clone which gets sent out as a PokemonEntity standing in the world. The clone is meant to be
 * strictly transient: the only thing that disposes of it is the post-battle
 * {@code recallWithAnimation()} hook attached by safeCopyOf.
 *
 * <p>Two things conspire to strand it. BattleRegistry#onPlayerDisconnect -- whose whole body is
 * {@code battle.stop()}, the call that triggers those recalls -- is cancelled for raids by
 * RaidBattleRegistryMixin, deliberately, so one player leaving cannot end the raid for everyone
 * else. And nothing ends a raid on SERVER_STOPPING either. So when a server stops, every player
 * disconnects, no raid battle is ever stopped, and any clone still standing in the world is saved
 * into its chunk like an ordinary entity -- PokemonEntity does not override shouldBeSaved, and
 * neither does anything between it and Entity. On the next boot it loads as a second, permanent
 * copy of that player's Pokemon, which is exactly what was reported: a duplicate standing at the
 * old raid site. The reporter's window, a stop landing between the fight ending and the reward
 * screen, is when the recall is in flight and the clone is at its most orphanable.
 *
 * <p>Fixing it at the save boundary rather than by tidying up at shutdown is deliberate: a battle
 * clone is transient by construction, so persisting one is never correct at any point in a battle,
 * and this closes every timing window at once instead of the one that happened to be reported.
 * Clones already written to disk by an earlier version are unaffected -- those have to be removed
 * by hand.
 */
@Mixin(Entity.class)
public abstract class BattleClonePersistenceMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$neverSaveBattleClones(CallbackInfoReturnable<Boolean> cir) {
        // Injected into Entity.shouldBeSaved, so this runs for every entity in every chunk save.
        // Failing open (letting the entity save) is the correct asymmetry: if we cannot tell whether
        // this is a battle clone, saving a clone costs a duplicate that an admin can delete, while
        // refusing to save a real Pokemon would destroy a player's.
        try {
            if ((Object) this instanceof PokemonEntity pokemon && pokemon.isBattleClone()) {
                cir.setReturnValue(false);
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:shouldBeSaved", ex);
        }
    }
}
