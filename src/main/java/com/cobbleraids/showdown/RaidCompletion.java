package com.cobbleraids.showdown;

import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.dispatch.DispatchResultKt;

/** Moves terminal raid victory out of the current damage instruction and to the front of Cobblemon's dispatch queue. */
public final class RaidCompletion {
    private RaidCompletion() {}

    public static void complete(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null || raid.getStatus() != RaidSession.Status.COMPLETED) return;

        // Raid Dens 0.11.4 does the same two important things when canonical raid HP reaches zero:
        // discard queued post-damage work, then put terminal handling at the front of Cobblemon's dispatch queue.
        // We preserve that ordering while using our own >raidwin protocol command.
        battle.getDispatches().clear();
        battle.dispatchToFront(() -> {
            RaidLifecycleCoordinator.requestVictory(raid);
            return DispatchResultKt.getGO();
        });
    }
}
