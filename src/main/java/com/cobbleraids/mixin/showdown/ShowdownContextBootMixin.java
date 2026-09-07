package com.cobbleraids.mixin.showdown;

import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-applies CobbleRaids' Showdown edits at the last moment before the simulator reads them.
 *
 * openConnection() is attemptUnbundle(); createContext(); boot(); -- so this injection point is
 * after every mod's unbundle-time file writes have finished and before any of those files has been
 * evaluated. That ordering is the whole point. ShowdownResourceLoaderMixin runs inside
 * attemptUnbundle, where the order between us and another mod's mixin on the same method is
 * whatever Fabric's mod load order happens to produce; mega_showdown, which replaces Cobblemon's
 * index.js wholesale with its own copy, was measured winning that race and leaving the running
 * simulator with no raid-patch.js hook at all. Repairing the file later, at SERVER_STARTED, is too
 * late: the JS context has already been built from the clobbered copy, so raid-patch never loads
 * and raid bosses silently take no damage for the rest of the session -- battles run, turns
 * commit, the boss just never loses HP.
 *
 * Kept alongside the unbundle-time install rather than replacing it: both are idempotent, and the
 * earlier one still gives a mod that reads (rather than writes) the files during unbundle something
 * correct to read.
 */
@Mixin(GraalShowdownService.class)
public abstract class ShowdownContextBootMixin {
    @Inject(
            method = "openConnection",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/cobblemon/mod/common/battles/runner/graal/GraalShowdownUnbundler;attemptUnbundle()V",
                    shift = At.Shift.AFTER))
    private void cobbleRaids$installBeforeContext(CallbackInfo ci) {
        ShowdownIntegrationInstaller.installSafely("before Showdown context creation");
    }
}
