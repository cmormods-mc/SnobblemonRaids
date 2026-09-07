package com.cobbleraids.mixin.showdown;

import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownUnbundler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs only CobbleRaids' integration files after Cobblemon unbundles its own Showdown copy.
 *
 * This is the first of two application points -- see ShowdownIntegrationInstaller.installSafely,
 * also called from SERVER_STARTED, for the idempotent re-check that repairs this if another mod's
 * own unbundle-time file writes clobber it afterward.
 *
 * We are on Cobblemon's "Cobblemon Showdown" thread here and nothing above us catches, so this must
 * not throw: an exception kills that thread and takes every battle in the game with it, raid or not.
 */
@Mixin(GraalShowdownUnbundler.class)
public abstract class ShowdownResourceLoaderMixin {
    @Inject(method = "attemptUnbundle", at = @At("RETURN"))
    private void cobbleRaids$loadIntegrationFiles(CallbackInfo ci) {
        ShowdownIntegrationInstaller.installSafely("at Showdown unbundle");
    }
}
