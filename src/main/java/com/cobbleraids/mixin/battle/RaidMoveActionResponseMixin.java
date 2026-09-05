package com.cobbleraids.mixin.battle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Serializes raid moves without a target suffix after server-side legal-target validation. */
@Mixin(MoveActionResponse.class)
public abstract class RaidMoveActionResponseMixin {
    @Inject(method = "toShowdownString", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$serializeRaidMove(
            ActiveBattlePokemon user,
            ShowdownMoveset moveset,
            CallbackInfoReturnable<String> cir
    ) {
        RaidSession raid = RaidRegistry.get(user.getBattle());
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE || moveset == null) return;

        MoveActionResponse response = (MoveActionResponse) (Object) this;
        int moveIndex = 0;
        for (int i = 0; i < moveset.getMoves().size(); i++) {
            InBattleMove candidate = moveset.getMoves().get(i);
            if (candidate.getId().equals(response.getMoveName())) {
                moveIndex = i + 1;
                break;
            }
        }
        if (moveIndex == 0) return;

        String serialized = "move " + moveIndex;
        if (response.getGimmickID() != null) serialized += " " + response.getGimmickID();
        cir.setReturnValue(serialized);
    }
}
