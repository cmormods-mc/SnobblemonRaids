package com.cobbleraids;

import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.command.RaidAdminCommand;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.interaction.RaidBossInteractionListener;
import com.cobbleraids.lifecycle.RaidBattleEventCoordinator;
import com.cobbleraids.lifecycle.RaidCombatRuleService;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.network.RaidRewardPayloads;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.reward.NativeRewardScreenGateway;
import com.cobbleraids.reward.RaidRewardCommand;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.showdown.RaidInstructionRegistrar;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobbleraids.spawn.RaidSpawnHistory;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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
        // Each subsystem gets its own barrier rather than one around the whole block: a lobby that
        // throws must not also cost that tick's spawn check, combat clock and glow refresh. The
        // method references are non-capturing, so this allocates nothing 20 times a second.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RaidFaultBarrier.safeTick("lobby", server, RaidLobbyManager::tick);
            RaidFaultBarrier.safeTick("spawning", server, RaidSpawnScheduler::tick);
            RaidFaultBarrier.safeTick("rewards", server, RaidRewardService::tick);
            RaidFaultBarrier.safeTick("combat-timer", server, RaidCombatRuleService::tick);
            RaidFaultBarrier.safeTick("boss-glow", server, RaidBossGlowService::tick);
        });
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> CobbleRaidsConfigManager.reload());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RewardGuiBackends.ensureReady());
        ServerLifecycleEvents.SERVER_STARTED.register(RaidSpawnScheduler::onServerStarted);
        // After SERVER_STARTED specifically: restoring a saved claim resolves its rewards from the
        // datapack registry, which is only populated once the initial resource load has finished.
        ServerLifecycleEvents.SERVER_STARTED.register(RaidRewardService::onServerStarted);
        // Raid history is the substrate every catch mechanic reads, so it is restored with the
        // rewards and cleared with everything else below.
        ServerLifecycleEvents.SERVER_STARTED.register(RaidPlayerRecords::onServerStarted);
        // Presents a reward that outlived a disconnect or restart. Without this the queue is
        // restored but nothing ever offers it, so the reveal screen is only ever seen by players
        // who happened to be online when the raid was won.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidRewardService.onPlayerJoin(handler.getPlayer()));
        // Repairs the Showdown integration (ShowdownResourceLoaderMixin) if another mod's own
        // unbundle-time file writes clobbered it after ours -- confirmed live against a real pack
        // (mega_showdown) that patches the same Cobblemon Showdown files at the same injection point.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ShowdownIntegrationInstaller.installSafely("at server start"));
        ServerLifecycleEvents.SERVER_STOPPING.register(RaidSpawnScheduler::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPING.register(RaidBossGlowService::onServerStopping);
        // Everything above needs a live server (discarding bosses, removing scoreboard teams).
        // Everything below is pure memory hygiene, so it waits for SERVER_STOPPED -- after every
        // world is closed and saved -- where it cannot race the write of a SavedData.
        //
        // These maps are per-server, but the classes holding them are not: an integrated
        // (single-player) client keeps this JVM for every world it opens, so anything not cleared
        // here is read back against the next world. Two of them, RaidRegistry and RaidLobbyManager,
        // hold a PokemonEntity, and an entity reaches its ServerLevel -- so a single raid left in
        // flight at shutdown pins the whole closed world in memory for as long as the game runs.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            int raids = RaidRegistry.onServerStopped();
            int lobbies = RaidLobbyManager.onServerStopped();
            RaidLifecycleCoordinator.onServerStopped();
            RaidCombatRuleService.onServerStopped();
            RaidRewardService.onServerStopped();
            RaidSpawnHistory.onServerStopped();
            RaidPlayerRecords.onServerStopped();
            RaidFaultBarrier.onServerStopped();
            // Only these two mean somebody lost progress, so only these two are worth a line on an
            // otherwise clean shutdown. Unclaimed rewards are not listed: those survive on disk.
            if (raids > 0 || lobbies > 0) {
                RaidLog.info("Server stopped with " + raids + " raid(s) in battle and "
                        + lobbies + " still recruiting; their progress is not resumable.");
            }
        });
        // A natural raid boss is persistence-required, so nothing else will ever remove one that
        // the scheduler has stopped tracking. Checking on load is the only point where an orphan
        // in a previously unloaded chunk becomes reachable.
        ServerEntityEvents.ENTITY_LOAD.register(RaidSpawnScheduler::onNaturalBossLoaded);
        // The converse: release a tracked boss's raid slot as soon as the entity is destroyed.
        // discard() removes it from ServerLevel's UUID lookup synchronously, so the scheduler's
        // once-a-second maintenance pass can never observe the removal itself and would hold the
        // slot against max_active_raids for the rest of the boss's despawn_seconds.
        ServerEntityEvents.ENTITY_UNLOAD.register(RaidSpawnScheduler::onEntityUnloaded);
        // A dimension-managing mod can close a ServerLevel outright (not just unload its chunks),
        // which would otherwise leave a tracked boss there occupying a raid slot until its despawn
        // timer expires, since it can never resolve again.
        ServerWorldEvents.UNLOAD.register(RaidSpawnScheduler::onLevelUnloaded);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RaidDefinitionRegistry());
    }
}
