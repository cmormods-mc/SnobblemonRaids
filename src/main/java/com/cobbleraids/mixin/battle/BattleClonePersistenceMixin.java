package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
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
 * into its chunk like an ordinary entity. On the next boot it loads as a second, permanent copy of
 * that player's Pokemon, which is exactly what was reported: a duplicate standing at the old raid
 * site. The reporter's window, a stop landing between the fight ending and the reward screen, is
 * when the recall is in flight and the clone is at its most orphanable.
 *
 * <p>Targets {@code PokemonEntity.shouldBeSaved} directly, not vanilla's
 * {@code Entity.shouldBeSaved}. An earlier version of this mixin targeted the vanilla method on the
 * strength of a claim in this comment -- "PokemonEntity does not override shouldBeSaved" -- that was
 * never actually checked and was wrong: decompiling the real jar shows {@code PokemonEntity} fully
 * overrides it (owner UUID, NPC ownership, the {@code save_pokemon_to_world} config, {@code
 * isPersistenceRequired()}, a cancelable event) and never calls up to {@code Entity}'s version. That
 * made the vanilla-targeted mixin dead code for every real Pokemon, including every raid boss and
 * battle clone -- caught by {@code validation/validate_mixin_target_shadowing.py}, which exists
 * because this is the second mixin in this codebase found shadowed this way (see
 * {@code RaidBossNameplateIconMixin} and docs/adr/0004-mixin-target-concrete-render-path.md), not the
 * first. {@code isPersistenceRequired()} being true for a raid boss is exactly what routed
 * {@code PokemonEntity}'s own logic toward "save it", so this was not a latent risk: it was this same
 * duplication bug, live again, silently.
 *
 * <p>Fixing it at the save boundary rather than by tidying up at shutdown is deliberate: a battle
 * clone is transient by construction, so persisting one is never correct at any point in a battle,
 * and this closes every timing window at once instead of the one that happened to be reported.
 * Clones already written to disk by an earlier version are unaffected -- those have to be removed
 * by hand.
 */
@Mixin(PokemonEntity.class)
public abstract class BattleClonePersistenceMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$neverSaveBattleClones(CallbackInfoReturnable<Boolean> cir) {
        // Failing open (letting the entity save) is the correct asymmetry: if we cannot tell whether
        // this is a battle clone, saving a clone costs a duplicate that an admin can delete, while
        // refusing to save a real Pokemon would destroy a player's.
        try {
            PokemonEntity self = (PokemonEntity) (Object) this;
            if (self.isBattleClone()) {
                cir.setReturnValue(false);
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:shouldBeSaved", ex);
        }
    }
}
