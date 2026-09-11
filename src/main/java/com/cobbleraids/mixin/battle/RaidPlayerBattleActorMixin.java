package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.net.NetworkPacket;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops a withdrawn raid participant from receiving later shared-battle UI/update packets. */
@Mixin(PlayerBattleActor.class)
public abstract class RaidPlayerBattleActorMixin {
    @Inject(method = "sendUpdate", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$suppressWithdrawnUpdates(NetworkPacket<?> packet, CallbackInfo ci) {
        try {
            PlayerBattleActor self = (PlayerBattleActor) (Object) this;
            if (!self.isInitialized()) return;
            RaidSession raid = RaidRegistry.get(self.getBattle());
            if (raid != null && !raid.isActiveParticipant(self.getUuid())) ci.cancel();
        } catch (Exception ex) {
            // Fails open: a withdrawn player receives a battle update they should not have. Harmless
            // next to an exception inside Cobblemon's packet dispatch.
            RaidFaultBarrier.report("mixin:sendUpdate", ex);
        }
    }
}
