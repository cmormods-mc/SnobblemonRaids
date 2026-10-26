package com.cobbleraids;

import com.cobbleraids.fault.RaidThreadGuard;
import com.cobbleraids.catching.DefeatedBossSnapshots;
import com.cobbleraids.catching.HallOfLegends;
import com.cobbleraids.catching.LegendRoomGateway;
import com.cobbleraids.catching.RaidCaptureSessionService;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.catching.TrophyLedger;
import com.cobbleraids.catching.TrophyRoomGateway;
import com.cobbleraids.stats.RaidStatsStore;
import com.cobbleraids.command.RaidCommands;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidRewardPolicyManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.fault.RaidConsistencyAuditScheduler;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.interaction.RaidBossInteractionListener;
import com.cobbleraids.item.RaidHeldItems;
import com.cobbleraids.item.RaidItemPack;
import com.cobbleraids.item.RaidKeyItems;
import com.cobbleraids.lifecycle.RaidBattleEventCoordinator;
import com.cobbleraids.lifecycle.RaidCombatRuleService;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.lifecycle.RaidReconnectService;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.placeholder.RaidPlaceholders;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.renown.RenownRegistry;
import com.cobbleraids.reward.NativeRewardScreenGateway;
import com.cobbleraids.reward.currency.RaidCurrencyBackends;
import com.cobbleraids.shop.RaidShopGateway;
import com.cobbleraids.web.WebUi;
import com.cobbleraids.shop.ShopCatalogManager;
import com.cobbleraids.shop.ShopRotationService;
import com.cobbleraids.showdown.RaidInstructionRegistrar;
import com.cobbleraids.showdown.ShowdownIntegrationInstaller;
import com.cobbleraids.spawn.RaidSpawnHistory;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobbleraids.presentation.TitleDisplayService;
import com.cobbleraids.title.TitleCatalogManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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
        RaidRewardPolicyManager.load();
        ShopCatalogManager.load();
        TitleCatalogManager.load();
        // Off by default: the World Boss event system does nothing until an operator enables it.
        RaidFaultBarrier.guard("startup:world-boss-config", com.cobbleraids.worldboss.WorldBossConfigManager::load);

        // Before anything that could look an item up by id, and before the reward policy is used:
        // a loot table naming an unregistered id costs the whole table, not just the entry.
        RaidItemPack.register();
        RaidKeyItems.register();
        RaidHeldItems.register();

        RaidInstructionRegistrar.register();
        RaidBattleEventCoordinator.register();
        // One class owns the registration order, which is load-bearing; see RaidCommands.
        RaidCommands.register();
        WebUi.register();
        RaidBossInteractionListener.register();
        RaidPlaceholders.registerIfPresent();
        // Each subsystem gets its own barrier rather than one around the whole block: a lobby that
        // throws must not also cost that tick's spawn check, combat clock and glow refresh. The
        // method references are non-capturing, so this allocates nothing 20 times a second.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RaidFaultBarrier.safeTick("lobby", server, RaidLobbyManager::tick);
            RaidFaultBarrier.safeTick("spawning", server, RaidSpawnScheduler::tick);
            RaidFaultBarrier.safeTick("rewards", server, RaidRewardService::tick);
            RaidFaultBarrier.safeTick("world-boss", server, com.cobbleraids.worldboss.WorldBossService::tick);
            RaidFaultBarrier.safeTick("capture-sessions", server, RaidCaptureSessionService::tick);
            RaidFaultBarrier.safeTick("reconnect-grace", server, RaidReconnectService::tick);
            RaidFaultBarrier.safeTick("combat-timer", server, RaidCombatRuleService::tick);
            RaidFaultBarrier.safeTick("boss-glow", server, RaidBossGlowService::tick);
            RaidFaultBarrier.safeTick("web-sounds", server, com.cobbleraids.web.WebSounds::tick);
            RaidFaultBarrier.safeTick("consistency-audit", server, RaidConsistencyAuditScheduler::tick);
            // Last, so a window's summary includes this very tick. It times itself too, which is the
            // honest cost of writing the report.
            RaidFaultBarrier.safeTick("perf-report", server, com.cobbleraids.perf.RaidPerfReporter::tick);
        });
        // Guarded like everything else, but for a different reason than the rest: reload() throws
        // deliberately on a malformed config, and CURRENT is only replaced after a successful parse,
        // so a running server already keeps its last known good settings. What the barrier adds is
        // that a bad config can no longer abort the *rest* of the data pack reload -- including this
        // mod's own definition loading, which registers later.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("config-reload", CobbleRaidsConfigManager::reload));
        // Same barrier, same reason: a malformed reward policy keeps the last good one rather than
        // aborting the reload that also brings in this mod's raid definitions.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("reward-policy-reload", RaidRewardPolicyManager::reload));
        // And the shop catalogue, so an operator can reprice without restarting the server.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("world-boss-config-reload", com.cobbleraids.worldboss.WorldBossConfigManager::reload));
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("shop-catalog-reload", ShopCatalogManager::reload));
        // Species and their labels can change on a reload, and the rotating page is built from them.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("shop-rotation-reload", ShopRotationService::invalidate));
        // And the title catalogue, for the same reason.
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) ->
                RaidFaultBarrier.guard("title-catalog-reload", TitleCatalogManager::reload));
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                RaidFaultBarrier.guard("startup:reward-currency", RaidCurrencyBackends::ensureReady));
        // First SERVER_STARTED listener on purpose: everything after it is worth checking against.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:thread-guard", () -> RaidThreadGuard.onServerStarted(server)));
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:spawn-scheduler", () -> RaidSpawnScheduler.onServerStarted(server)));
        // After the spawn scheduler's sweep above, which discards every stale raid boss: an event that
        // survived a restart re-creates its showpiece here.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:world-boss", () -> com.cobbleraids.worldboss.WorldBossService.onServerStarted(server)));
        // After SERVER_STARTED specifically: restoring a saved claim resolves its rewards from the
        // datapack registry, which is only populated once the initial resource load has finished.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:rewards", () -> RaidRewardService.onServerStarted(server)));
        // Same reasoning as rewards above: a restored session's banked RP fallback resolves against
        // the live raid definition registry, so this waits for SERVER_STARTED too.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:capture-sessions", () -> RaidCaptureSessionService.onServerStarted(server)));
        // Raid history is the substrate every catch mechanic reads, so it is restored with the
        // rewards and cleared with everything else below.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:player-records", () -> RaidPlayerRecords.onServerStarted(server)));
        // Same substrate, same reason: a snapshot bought back is still only readable once the
        // datapack's raid definitions (rarity tiers) are loaded.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:boss-snapshots", () -> DefeatedBossSnapshots.onServerStarted(server)));
        // Same substrate again: the trophy room is a permanent history record, restored alongside
        // the other two rather than lazily on first open.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:trophy-ledger", () -> TrophyLedger.onServerStarted(server)));
        // The server-wide sibling of the trophy ledger just above: same substrate, same restore point.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:hall-of-legends", () -> HallOfLegends.onServerStarted(server)));
        // After the player records and the trophy ledger: the first start seeds the leaderboards from
        // what those two already hold, so they have to be restored first.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaidFaultBarrier.guard("startup:raid-stats", () -> RaidStatsStore.onServerStarted(server)));
        // Presents a reward that outlived a disconnect or restart. Without this the queue is
        // restored but nothing ever offers it, so the reveal screen is only ever seen by players
        // who happened to be online when the raid was won.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join", () -> RaidRewardService.onPlayerJoin(handler.getPlayer())));
        // Retries a capture whose delivery was pending on party/PC room the moment its player logs
        // back in, same reasoning as the reward re-offer just above.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join:capture-sessions",
                        () -> RaidCaptureSessionService.onPlayerJoin(handler.getPlayer())));
        // Per-player request budgets and a log throttle live in plain maps; drop a player's entry on
        // leaving so they cannot accumulate over a long uptime.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                RaidFaultBarrier.guard("player-disconnect:request-throttles", () -> {
                    TrophyRoomGateway.forget(handler.getPlayer().getUUID());
                    LegendRoomGateway.forget(handler.getPlayer().getUUID());
                    RaidCaptureSessionService.forgetLogThrottle(handler.getPlayer().getUUID());
                }));
        // Resumes a raid a player was mid-disconnect-grace on -- see RaidReconnectService.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join:raid-reconnect",
                        () -> RaidReconnectService.onPlayerJoin(handler.getPlayer(), server)));
        // Re-applies a selected title's scoreboard team on every join -- team membership does not
        // survive a relog, unlike the record it is derived from.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join:title-display",
                        () -> TitleDisplayService.apply(server, handler.getPlayer())));
        // Repairs the Showdown integration (ShowdownResourceLoaderMixin) if another mod's own
        // unbundle-time file writes clobbered it after ours -- confirmed live against a real pack
        // (mega_showdown) that patches the same Cobblemon Showdown files at the same injection point.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> RaidFaultBarrier.guard("startup:showdown",
                () -> ShowdownIntegrationInstaller.installSafely("at server start")));
        // First: ends every raid still in battle before anything below runs or the world saves, so
        // no boss is ever written to disk mid-fight. See RaidLifecycleCoordinator.abortAll.
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RaidFaultBarrier.guard("shutdown:abort-active-raids", RaidLifecycleCoordinator::abortAll));
        // So the last partial window of a session is on disk rather than lost with the process.
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RaidFaultBarrier.guard("shutdown:perf-report", () -> com.cobbleraids.perf.RaidPerfReporter.report(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RaidFaultBarrier.guard("shutdown:world-boss", () -> com.cobbleraids.worldboss.WorldBossService.onServerStopping(server)));
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
            RaidFaultBarrier.guard("shutdown:world-boss-state", com.cobbleraids.worldboss.WorldBossService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:encounters", com.cobbleraids.encounter.EncounterService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:combat-rules", RaidCombatRuleService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:reconnect-grace", RaidReconnectService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:rewards", RaidRewardService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:web-sounds", com.cobbleraids.web.WebSounds::clear);
            RaidFaultBarrier.guard("shutdown:capture-sessions", RaidCaptureSessionService::onServerStopped);
            RaidFaultBarrier.guard("shutdown:spawn-history", RaidSpawnHistory::onServerStopped);
            RaidFaultBarrier.guard("shutdown:player-records", RaidPlayerRecords::onServerStopped);
            RaidFaultBarrier.guard("shutdown:boss-snapshots", DefeatedBossSnapshots::onServerStopped);
            RaidFaultBarrier.guard("shutdown:trophy-ledger", TrophyLedger::onServerStopped);
            RaidFaultBarrier.guard("shutdown:hall-of-legends", HallOfLegends::onServerStopped);
            RaidFaultBarrier.guard("shutdown:raid-stats", RaidStatsStore::onServerStopped);
            int raids = counts[0];
            int lobbies = counts[1];
            RaidFaultBarrier.onServerStopped();
            com.cobbleraids.perf.RaidPerf.onServerStopped();
            com.cobbleraids.perf.RaidPerfReporter.onServerStopped();
            RaidThreadGuard.onServerStopped();
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
        // The same gap for owned encounters: a boss left by a crash usually loads after the
        // SERVER_STARTED sweep has already run.
        ServerEntityEvents.ENTITY_LOAD.register(RaidFaultBarrier.entityLoad("entity-load:owned-encounter",
                com.cobbleraids.encounter.EncounterService::onEntityLoaded));
        // The same gap again, for the glow service's own tracking: a boss whose raid survived a
        // restart never went through RaidBossSpawner.spawnAt(), so it has no TRACKED entry until it
        // reloads here -- otherwise its (still legitimate) scoreboard membership looks orphaned to the
        // consistency audit, and can never be cleaned up for real once the boss is later destroyed.
        ServerEntityEvents.ENTITY_LOAD.register(
                RaidFaultBarrier.entityLoad("entity-load:glow", RaidBossGlowService::onEntityLoaded));
        // The converse: release a tracked boss's raid slot as soon as the entity is destroyed.
        // discard() removes it from ServerLevel's UUID lookup synchronously, so the scheduler's
        // once-a-second maintenance pass can never observe the removal itself and would hold the
        // slot against max_active_raids for the rest of the boss's despawn_seconds.
        ServerEntityEvents.ENTITY_UNLOAD.register(
                RaidFaultBarrier.entityUnload("entity-unload", RaidSpawnScheduler::onEntityUnloaded));
        // The glow service tracks the same bosses and needs the same signal: a boss that merely
        // unloaded has to stay tracked so it glows again when its chunk returns, so destruction is
        // the only thing that may untrack it -- and untracking is also what takes it back out of its
        // scoreboard team, which is saved into the world.
        ServerEntityEvents.ENTITY_UNLOAD.register(
                RaidFaultBarrier.entityUnload("entity-unload:glow", RaidBossGlowService::onEntityUnloaded));
        // A dimension-managing mod can close a ServerLevel outright (not just unload its chunks),
        // which would otherwise leave a tracked boss there occupying a raid slot until its despawn
        // timer expires, since it can never resolve again.
        ServerWorldEvents.UNLOAD.register((server, level) ->
                RaidFaultBarrier.guard("level-unload", () -> RaidSpawnScheduler.onLevelUnloaded(server, level)));
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RaidDefinitionRegistry());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new RenownRegistry());
    }
}
