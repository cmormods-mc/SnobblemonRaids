package com.cobbleraids;

import com.cobbleraids.command.RaidAdminCommand;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.interaction.RaidBossInteractionListener;
import com.cobbleraids.lifecycle.RaidBattleEventCoordinator;
import com.cobbleraids.lifecycle.RaidCombatRuleService;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.network.RaidRewardPayloads;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.reward.NativeRewardScreenGateway;
import com.cobbleraids.reward.RaidRewardCommand;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.showdown.RaidInstructionRegistrar;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

        RaidRewardPayloads.registerPayloadTypes();
        ServerPlayNetworking.registerGlobalReceiver(RewardChoicePayload.TYPE, (payload, context) ->
                context.server().execute(() -> NativeRewardScreenGateway.handleChoice(context.player(), payload)));

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
            RaidBossGlowService.tick(server);
        });
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> CobbleRaidsConfigManager.reload());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RewardGuiBackends.ensureReady());
        ServerLifecycleEvents.SERVER_STARTED.register(RaidSpawnScheduler::onServerStarted);
        // Repairs the Showdown integration (ShowdownResourceLoaderMixin) if another mod's own
        // unbundle-time file writes clobbered it after ours -- confirmed live against a real pack
        // (mega_showdown) that patches the same Cobblemon Showdown files at the same injection point.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ShowdownIntegrationInstaller.ensureInstalled());
        ServerLifecycleEvents.SERVER_STOPPING.register(RaidSpawnScheduler::onServerStopping);
        // A natural raid boss is persistence-required, so nothing else will ever remove one that
        // the scheduler has stopped tracking. Checking on load is the only point where an orphan
        // in a previously unloaded chunk becomes reachable.
        ServerEntityEvents.ENTITY_LOAD.register(RaidSpawnScheduler::onNaturalBossLoaded);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RaidDefinitionRegistry());
    }
}
