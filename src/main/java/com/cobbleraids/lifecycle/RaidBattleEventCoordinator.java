package com.cobbleraids.lifecycle;

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

    private static void onVictory(BattleVictoryEvent event) { RaidLifecycleCoordinator.onBattleVictory(event); }

    private static void onFainted(BattleFaintedEvent event) {
        if (RaidRegistry.get(event.getBattle()) != null) RaidLifecycleCoordinator.onBattleFainted(event.getBattle());
    }

    private static void onFled(BattleFledEvent event) {
        RaidSession raid = RaidRegistry.get(event.getBattle());
        if (raid != null) RaidLifecycleCoordinator.onPlayerFled(event.getBattle(), event.getPlayer().getUuid());
    }
}
