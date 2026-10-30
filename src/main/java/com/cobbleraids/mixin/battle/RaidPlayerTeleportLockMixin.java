package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidTeleportLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player in a raid cannot be teleported out of it.
 *
 * <p>Sits on ServerPlayer's own teleport and dimension methods rather than on any one command, so
 * /tp, home and warp mods, /back, portals and pearls are all refused without naming them. Fails open
 * on a fault: a player who can teleport is the old behaviour, a thrown exception is worse.
 */
@Mixin(ServerPlayer.class)
public abstract class RaidPlayerTeleportLockMixin {

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$lockLevelTeleport(CallbackInfo ci) {
        try {
            if (cobbleRaids$refuse()) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:teleport-lock", ex);
        }
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$lockPositionTeleport(CallbackInfo ci) {
        try {
            if (cobbleRaids$refuse()) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:teleport-lock", ex);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$lockRelativeTeleport(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cobbleRaids$refuse()) cir.setReturnValue(false);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:teleport-lock", ex);
        }
    }

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$lockDimensionChange(CallbackInfoReturnable<Entity> cir) {
        try {
            if (cobbleRaids$refuse()) cir.setReturnValue(null);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:teleport-lock", ex);
        }
    }

    private boolean cobbleRaids$refuse() {
        return RaidTeleportLock.refuse((ServerPlayer) (Object) this);
    }
}
