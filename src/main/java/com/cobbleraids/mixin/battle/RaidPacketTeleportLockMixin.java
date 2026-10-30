package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidTeleportLock;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The packet-level backstop for {@link RaidPlayerTeleportLockMixin}.
 *
 * <p>Every teleport ends in ServerGamePacketListenerImpl.teleport, so a warp mod that calls it
 * directly instead of going through ServerPlayer is refused here too. That is what lets the lock
 * hold against a warp, home or back mod nobody has looked at yet. The five-argument overload
 * delegates to this one, so this single injection covers both.
 *
 * <p>A teleport to the player's own position is let through: vanilla answers a rejected move with
 * exactly that, and it moves nobody.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RaidPacketTeleportLockMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$lockPacketTeleport(double x, double y, double z, float yaw, float pitch,
                                                Set<RelativeMovement> relative, CallbackInfo ci) {
        try {
            if (player == null || RaidTeleportLock.isInPlace(player, x, y, z, relative)) return;
            if (RaidTeleportLock.refuse(player)) ci.cancel();
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:packet-teleport-lock", ex);
        }
    }
}
