package com.cobbleraids.lifecycle;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFaintedEvent;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;

/** Bridges Cobblemon battle lifecycle events into the raid lifecycle state machine. */
public final class RaidBattleEventCoordinator {
    private static boolean registered;
    private RaidBattleEventCoordinator() {}

    public static synchronized void register() {
        if (registered) return;
        CobblemonEvents.BATTLE_VICTORY.subscribe(RaidBattleEventCoordinator::onVictory);
        CobblemonEvents.BATTLE_FAINTED.subscribe(RaidBattleEventCoordinator::onFainted);
        CobblemonEvents.BATTLE_FLED.subscribe(RaidBattleEventCoordinator::onFled);
        registered = true;
    }

    // Cobblemon emits these from its own battle update loop on the server thread, so an exception
    // thrown back into it does not merely lose the raid -- it unwinds through Cobblemon's event
    // dispatch and onto the tick. Every raid terminal path arrives through one of these three, and
    // they are the paths with the most edge cases (simultaneous faints, a fled host, a battle that
    // Showdown already ended), which is exactly why they are also the ones worth containing.
    private static void onVictory(BattleVictoryEvent event) {
        try {
            RaidLifecycleCoordinator.onBattleVictory(event);
        } catch (Exception ex) {
            RaidFaultBarrier.report("battle-victory", ex);
        }
    }

    private static void onFainted(BattleFaintedEvent event) {
        try {
            if (RaidRegistry.get(event.getBattle()) != null) RaidLifecycleCoordinator.onBattleFainted(event.getBattle());
        } catch (Exception ex) {
            RaidFaultBarrier.report("battle-fainted", ex);
        }
    }

    private static void onFled(BattleFledEvent event) {
        try {
            RaidSession raid = RaidRegistry.get(event.getBattle());
            if (raid != null) RaidLifecycleCoordinator.onPlayerFled(event.getBattle(), event.getPlayer().getUuid());
        } catch (Exception ex) {
            RaidFaultBarrier.report("battle-fled", ex);
        }
    }
}
