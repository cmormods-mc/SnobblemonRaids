package com.cobbleraids.mixin.showdown;

import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownUnbundler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs only CobbleRaids' integration files after Cobblemon unbundles its own Showdown copy.
 * The one simulator edit is an exact-text, fail-fast patch against the supplied 1.7.3 dex-formats.js.
 *
 * This is the first of two application points -- see ShowdownIntegrationInstaller.ensureInstalled(),
 * called from SERVER_STARTED, for the idempotent re-check that repairs this if another mod's own
 * unbundle-time file writes clobber it afterward.
 */
@Mixin(GraalShowdownUnbundler.class)
public abstract class ShowdownResourceLoaderMixin {
    @Inject(method = "attemptUnbundle", at = @At("RETURN"))
    private void cobbleRaids$loadIntegrationFiles(CallbackInfo ci) {
        ShowdownIntegrationInstaller.install();
    }
}
