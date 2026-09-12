package com.cobbleraids.mixin.battle;

import com.cobbleraids.battle.RaidMoveTargeting;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.Targetable;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a raid move's target legal, and then serializes it without one.
 *
 * <p>Two halves of the same problem. Cobblemon positions actors on a mirrored field and works out
 * "the slot opposite me" from the field width; a raid is one boss facing up to four separately
 * actored players, which that model cannot express. RaidActiveBattlePokemonMixin replaces the
 * geometry -- but only where a raid is known to exist, and a raid is known only on the server:
 * RaidRegistry is server state. A player's client therefore falls back to Cobblemon's own maths
 * and picks the wrong slot.
 *
 * <p>Seen live in a three-player raid, where the actors are p1a, p2b and p3c with the boss at p4a.
 * The first player's client aimed Leafage at p2b -- a teammate -- and the server rightly refused a
 * foe-move at an ally, so every move came back "Invalid action choice" and the raid could not
 * proceed. Solo raids never showed it because the mirrored slot happens to be the boss.
 *
 * <p>Fixed by correcting the target rather than by waiving the check. The submitted target is
 * repointed at the boss before Cobblemon validates, so PP, disabled moves and everything else are
 * still enforced exactly as they would be in any other battle. Nothing is lost by overriding the
 * player's choice either: toShowdownString below strips the target entirely, because raid-patch.js
 * already aims every player's move at the boss.
 */
@Mixin(MoveActionResponse.class)
public abstract class RaidMoveActionResponseMixin {
    /**
     * Repoints a raid move at the boss when the client picked an illegal slot.
     *
     * <p>Runs at HEAD of the validation Cobblemon already performs, so a corrected target is what
     * gets validated. Leaves a target that is already a legal opponent alone, and leaves every
     * non-raid battle untouched.
     */
    @Inject(method = "isValid", at = @At("HEAD"))
    private void cobbleRaids$retargetRaidMove(
            ActiveBattlePokemon user,
            ShowdownMoveset moveset,
            boolean forceSwitch,
            CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            RaidSession raid = RaidRegistry.get(user.getBattle());
            if (raid == null) return;

            MoveActionResponse response = (MoveActionResponse) (Object) this;
            String target = response.getTargetPnx();
            if (target == null) return;

            List<String> opponents = new ArrayList<>();
            for (Targetable opponent : user.getAdjacentOpponents()) {
                if (opponent instanceof ActiveBattlePokemon active) opponents.add(active.getPNX());
            }
            // The decision itself lives in RaidMoveTargeting, where it can be tested without a
            // battle; this half only turns opponents into the slots they occupy.
            String corrected = RaidMoveTargeting.correctedTarget(target, opponents);
            if (corrected != null) response.setTargetPnx(corrected);
        } catch (Exception ex) {
            // Falls through to Cobblemon's own validation, which will refuse the move rather than
            // let a raid proceed on an unvalidated action.
            RaidFaultBarrier.report("mixin:isValid", ex);
        }
    }

    @Inject(method = "toShowdownString", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$serializeRaidMove(
            ActiveBattlePokemon user,
            ShowdownMoveset moveset,
            CallbackInfoReturnable<String> cir
    ) {
        try {
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
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:toShowdownString", ex);
        }
    }
}
