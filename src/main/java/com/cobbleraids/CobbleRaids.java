package com.cobbleraids;

import com.cobbleraids.command.RaidAdminCommand;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.interaction.RaidBossInteractionListener;
import com.cobbleraids.lifecycle.RaidBattleEventCoordinator;
import com.cobbleraids.lifecycle.RaidCombatRuleService;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.reward.RaidRewardCommand;
import com.cobbleraids.reward.RaidRewardGuiInstaller;
import com.cobbleraids.showdown.RaidInstructionRegistrar;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;

/** CobbleRaids always runs alongside Cobblemon; CobbleBoss/Raid Dens are reference-only. */
public final class CobbleRaids implements ModInitializer {
    public static final String MOD_ID = "cobbleraids";

    @Override
    public void onInitialize() {
        // Load operator defaults before datapack raid definitions are prepared, because omitted
        // per-raid fields inherit values from config/cobbleraids/server.json.
        CobbleRaidsConfigManager.load();

        RaidInstructionRegistrar.register();
        RaidBattleEventCoordinator.register();
        RaidRewardCommand.register();
        RaidAdminCommand.register();
        RaidBossInteractionListener.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RaidLobbyManager.tick(server);
            RaidSpawnScheduler.tick(server);
            RaidRewardService.tick(server);
            RaidCombatRuleService.tick(server);
        });
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> CobbleRaidsConfigManager.reload());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RaidRewardGuiInstaller.ensureInstalledAndLoaded());
        ServerLifecycleEvents.SERVER_STARTED.register(RaidSpawnScheduler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(RaidSpawnScheduler::onServerStopping);
        // A natural raid boss is persistence-required, so nothing else will ever remove one that
        // the scheduler has stopped tracking. Checking on load is the only point where an orphan
        // in a previously unloaded chunk becomes reachable.
        ServerEntityEvents.ENTITY_LOAD.register(RaidSpawnScheduler::onNaturalBossLoaded);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RaidDefinitionRegistry());
    }
}
