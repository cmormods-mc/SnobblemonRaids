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
                context.server().execute(() -> RaidFaultBarrier.guard("reward-choice-packet",
                        () -> NativeRewardScreenGateway.handleChoice(context.player(), payload))));

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
        // Guarded like everything else, but for a different reason than the rest: reload() throws
        // deliberately on a malformed config, and CURRENT is only replaced after a successful parse,
        // so a running server already keeps its last known good settings. What the barrier adds is
        // that a bad config can no longer abort the *rest* of the data pack reload -- including this
        // mod's own definition loading, which registers later.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("config-reload", CobbleRaidsConfigManager::reload));
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                RaidFaultBarrier.guard("startup:reward-gui", RewardGuiBackends::ensureReady));
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:spawn-scheduler", () -> RaidSpawnScheduler.onServerStarted(server)));
        // After SERVER_STARTED specifically: restoring a saved claim resolves its rewards from the
        // datapack registry, which is only populated once the initial resource load has finished.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:rewards", () -> RaidRewardService.onServerStarted(server)));
        // Raid history is the substrate every catch mechanic reads, so it is restored with the
        // rewards and cleared with everything else below.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:player-records", () -> RaidPlayerRecords.onServerStarted(server)));
        // Presents a reward that outlived a disconnect or restart. Without this the queue is
        // restored but nothing ever offers it, so the reveal screen is only ever seen by players
        // who happened to be online when the raid was won.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join", () -> RaidRewardService.onPlayerJoin(handler.getPlayer())));
        // Repairs the Showdown integration (ShowdownResourceLoaderMixin) if another mod's own
        // unbundle-time file writes clobbered it after ours -- confirmed live against a real pack
        // (mega_showdown) that patches the same Cobblemon Showdown files at the same injection point.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> RaidFaultBarrier.guard("startup:showdown",
                () -> ShowdownIntegrationInstaller.installSafely("at server start")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RaidFaultBarrier.guard("shutdown:spawn-scheduler", () -> RaidSpawnScheduler.onServerStopping(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RaidFaultBarrier.guard("shutdown:boss-glow", () -> RaidBossGlowService.onServerStopping(server)));
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
            // Guarded one by one rather than as a block. Every line here exists to release memory
            // that would otherwise be read back against the next world an integrated client opens,
            // and two of them hold a PokemonEntity -- which reaches its ServerLevel, so a single
            // missed clear pins a whole closed world. One failing clear must not cost the others.
            int[] counts = new int[2];
            RaidFaultBarrier.guard("shutdown:raids", () -> counts[0] = RaidRegistry.onServerStopped());
            RaidFaultBarrier.guard("shutdown:lobbies", () -> counts[1] = RaidLobbyManager.onServerStopped());
            RaidFaultBarrier.guard("shutdown:lifecycle", RaidLifecycleCoordinator::onServerStopped);
            RaidFaultBarrier.guard("shutdown:combat-rules", RaidCombatRuleService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:rewards", RaidRewardService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:spawn-history", RaidSpawnHistory::onServerStopped);
            RaidFaultBarrier.guard("shutdown:player-records", RaidPlayerRecords::onServerStopped);
            int raids = counts[0];
            int lobbies = counts[1];
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
        ServerEntityEvents.ENTITY_LOAD.register(
                RaidFaultBarrier.entityLoad("entity-load", RaidSpawnScheduler::onNaturalBossLoaded));
        // The converse: release a tracked boss's raid slot as soon as the entity is destroyed.
        // discard() removes it from ServerLevel's UUID lookup synchronously, so the scheduler's
        // once-a-second maintenance pass can never observe the removal itself and would hold the
        // slot against max_active_raids for the rest of the boss's despawn_seconds.
        ServerEntityEvents.ENTITY_UNLOAD.register(
                RaidFaultBarrier.entityUnload("entity-unload", RaidSpawnScheduler::onEntityUnloaded));
        // A dimension-managing mod can close a ServerLevel outright (not just unload its chunks),
        // which would otherwise leave a tracked boss there occupying a raid slot until its despawn
        // timer expires, since it can never resolve again.
        ServerWorldEvents.UNLOAD.register((server, level) ->
                RaidFaultBarrier.guard("level-unload", () -> RaidSpawnScheduler.onLevelUnloaded(server, level)));
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RaidDefinitionRegistry());
    }
}
