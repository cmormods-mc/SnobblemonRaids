package com.cobbleraids.mixin.battle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.platform.events.ServerPlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cobblemon's player-death listener stops the entire battle before Fabric AFTER_DEATH runs.
 * A shared raid must instead withdraw just the dead player. Keep vanilla death and PC-link cleanup.
 */
@Mixin(value = Cobblemon.class, remap = false)
public abstract class RaidPlayerDeathMixin {
    @Redirect(method = "preInitialize$lambda$2",
            at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;stop()V"),
            remap = false)
    private static void cobbleRaids$withdrawDeadPlayer(PokemonBattle battle, ServerPlayerEvent.Death event) {
        try {
            RaidSession raid = RaidRegistry.get(battle);
            if (raid == null) {
                battle.stop();
            } else if (raid.getStatus() == RaidSession.Status.ACTIVE && !battle.getEnded()) {
                // Already withdrawn actors stay inert; never fall back to stopping their teammates.
                RaidLifecycleCoordinator.withdrawPlayer(raid, event.getPlayer());
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:raidPlayerDeath", ex);
        }
    }
}
