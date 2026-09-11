package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks external impulses on a raid boss, through the two different paths vanilla uses.
 *
 * <p>Entity#push covers being walked into or shoved by another entity, boats and minecarts, and
 * flowing water. It is the "something outside you is adding to your velocity" method, distinct from
 * the entity's own movement, which goes through travel/setDeltaMovement and is untouched here.
 *
 * <p>Explosions do NOT go through it, which a live TNT test caught after this mixin was first
 * written with only the push hook: Explosion#explode reads getDeltaMovement and writes
 * setDeltaMovement itself, so a boss with push cancelled still got launched 1.8 blocks. The
 * vanilla-intended opt-out is ignoreExplosion, which makes explode skip the entity outright --
 * damage and knockback together -- so that is what the second injector returns.
 *
 * <p>Knockback resistance does not cover either case; it only applies inside LivingEntity#knockback,
 * which is the melee and projectile path. And damage immunity does not help, because knockback is
 * applied independently of damage: a boss immune to TNT could still be thrown across the map by it,
 * which ruins an event just as effectively as killing it.
 *
 * <p>Targets Entity rather than PokemonEntity because neither method is overridden further down, so
 * this runs for every entity in the world. The instanceof is checked first and rejects everything
 * that is not a Pokemon before any tag or config lookup happens.
 */
@Mixin(Entity.class)
public abstract class RaidBossPushImmunityMixin {
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidBossUnpushable(double x, double y, double z, CallbackInfo ci) {
        // Entity.push runs on every collision resolution, so this is one of the hottest injections
        // in the mod. An untaken try/catch costs nothing; an escaping exception costs the server.
        try {
            if (cobbleRaids$isProtectedBoss()) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:push", ex);
        }
    }

    @Inject(method = "ignoreExplosion", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidBossIgnoresExplosions(Explosion explosion, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cobbleRaids$isProtectedBoss()) cir.setReturnValue(true);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:ignoreExplosion", ex);
        }
    }

    private boolean cobbleRaids$isProtectedBoss() {
        if (!((Object) this instanceof PokemonEntity self)) return false;
        if (!RaidBossEntityMarker.isRaidBoss(self)) return false;
        return CobbleRaidsConfigManager.get().bossMovement().preventKnockback();
    }
}
