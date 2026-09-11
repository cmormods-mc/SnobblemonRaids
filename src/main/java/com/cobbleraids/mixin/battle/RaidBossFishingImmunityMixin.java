package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a fishing rod reeling a raid boss around.
 *
 * <p>Worth its own mixin because FishingHook#pullEntity is the one knockback path that escapes
 * everything else: it neither deals damage nor calls push or knockback, it just adds to the
 * target's delta movement directly. So a boss that is invulnerable, knockback-resistant and
 * unpushable can still be dragged off its spawn by any player with a rod -- which makes it the
 * cheapest griefing tool available and the one most likely to be found by accident.
 */
@Mixin(FishingHook.class)
public abstract class RaidBossFishingImmunityMixin {
    @Inject(method = "pullEntity", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidBossUnreelable(Entity entity, CallbackInfo ci) {
        try {
            if (!(entity instanceof PokemonEntity pokemon)) return;
            if (!RaidBossEntityMarker.isRaidBoss(pokemon)) return;
            if (CobbleRaidsConfigManager.get().bossMovement().preventKnockback()) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:pullEntity", ex);
        }
    }
}
