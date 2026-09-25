package com.cobbleraids.lifecycle;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.CobblemonNetwork;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Holds a disconnected raider's spot instead of forfeiting it immediately.
 *
 * <p>Before this, {@link RaidLifecycleCoordinator#onPlayerDisconnected} ran the instant Cobblemon
 * reported the disconnect: a dropped connection cost exactly as much as deliberately withdrawing.
 * This sits in front of that call. While {@link CobbleRaidsConfig.ReconnectGrace} is enabled, a
 * disconnect starts a grace window instead; only once it lapses without the player coming back does
 * the original forfeiting path finally run, unchanged, from {@link #tick}.
 *
 * <p><b>Multiplayer, with someone else still actively playing:</b> the disconnected side is held
 * with the Showdown patch's own {@code >raidhold} (see raid-patch.js), which reuses the exact
 * passivation machinery {@code >raidleave} already relies on to keep a shared turn from stalling on
 * a side that will never answer -- reversibly this time. The raid keeps moving for everyone else;
 * the held player's Pokemon take no actions and, per raid-patch.js's own foes()/allies() gating on
 * the same flag, cannot be targeted by the boss either.
 *
 * <p><b>Solo, or the last remaining active player:</b> nobody else is present to be stalled, so
 * nothing is sent to Showdown at all. The shared turn is already waiting on this actor's choice and
 * simply keeps waiting -- Cobblemon's own actor lookup re-resolves a {@code ServerPlayer} by UUID on
 * every access, so nothing here goes stale while they are gone. {@link RaidCombatRuleService} also
 * stops advancing this raid's own time-limit clock for exactly this case, via
 * {@link #hasActivePresence}, so the raid cannot time out from under a player who is just reconnecting.
 *
 * <p>{@link #onPlayerJoin} un-holds a held side with {@code >raidresume} and, after the same
 * loading-screen delay {@link RaidRewardService} already uses for its own join-time screen, resends
 * the battle's init/log/pending-request packets so the client's battle UI reappears without the
 * player needing to do anything else.
 */
public final class RaidReconnectService {
    /** How long after JOIN the battle UI is resent -- see RaidRewardService.JOIN_OPEN_DELAY_TICKS. */
    private static final int RESUME_OPEN_DELAY_TICKS = 40;

    private record GraceHold(UUID raidId, long deadlineTick, boolean heldOnShowdown) {}
    private record PendingResume(UUID raidId, long readyTick, boolean heldOnShowdown) {}

    private static final Map<UUID, GraceHold> HOLDS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingResume> RESUMES = new ConcurrentHashMap<>();

    private RaidReconnectService() {}

    /**
     * Entry point for {@code RaidBattleRegistryMixin}'s disconnect hook, replacing a direct call to
     * {@link RaidLifecycleCoordinator#onPlayerDisconnected}. Falls straight through to it when the
     * feature is disabled, so a server that turns this off gets exactly today's behavior back.
     */
    public static void onPlayerDisconnected(RaidSession raid, ServerPlayer player, MinecraftServer server) {
        if (raid == null || player == null || server == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        UUID playerId = player.getUUID();
        if (!raid.isActiveParticipant(playerId)) return;

        CobbleRaidsConfig.ReconnectGrace config = CobbleRaidsConfigManager.get().reconnectGrace();
        if (!config.enabled()) {
            RaidLifecycleCoordinator.onPlayerDisconnected(raid, playerId);
            return;
        }

        // "Someone else is still actively here" excludes both a player who withdrew earlier (already
        // gone from activeParticipants) and one already mid-grace themselves -- so a second disconnect
        // in an already-fully-held raid correctly falls into the no-passivation branch below too.
        boolean othersStillActive = raid.getActiveParticipants().stream()
                .anyMatch(id -> !id.equals(playerId) && !HOLDS.containsKey(id));

        PokemonBattle battle = raid.getBattle();
        BattleActor actor = battle.getActor(player);
        if (othersStillActive && actor != null && actor.getShowdownId() != null && !battle.getEnded()) {
            battle.writeShowdownAction(">raidhold " + actor.getShowdownId());
        }

        long deadline = server.getTickCount() + (long) config.graceSeconds() * 20L;
        HOLDS.put(playerId, new GraceHold(raid.getId(), deadline, othersStillActive));
        RaidLog.info("Holding " + player.getGameProfile().getName() + "'s raid slot for "
                + config.graceSeconds() + "s after a disconnect.");
        announceOthers(server, raid, playerId, Component.literal(player.getGameProfile().getName()
                + " lost connection. Holding their raid slot for " + config.graceSeconds() + "s.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** Entry point for the JOIN event. A no-op for any player without a pending hold. */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) return;
        UUID playerId = player.getUUID();
        GraceHold hold = HOLDS.remove(playerId);
        if (hold == null) return;

        RaidSession raid = findRaid(hold.raidId());
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;

        PokemonBattle battle = raid.getBattle();
        if (hold.heldOnShowdown() && !battle.getEnded()) {
            BattleActor actor = battle.getActor(player);
            if (actor != null && actor.getShowdownId() != null) {
                battle.writeShowdownAction(">raidresume " + actor.getShowdownId());
            }
        }

        player.sendSystemMessage(Component.literal("Reconnecting you to your raid...")
                .withStyle(ChatFormatting.YELLOW));
        announceOthers(server, raid, playerId, Component.literal(player.getGameProfile().getName()
                + " has reconnected.").withStyle(ChatFormatting.GREEN));

        // Deferred the same way RaidRewardService defers its own join-time screen: the client is
        // still finishing its terrain load right after JOIN, and a screen pushed into that window is
        // drawn behind the loading overlay and dismissed with it, which looks exactly like the battle
        // UI silently failing to reopen.
        RESUMES.put(playerId, new PendingResume(hold.raidId(),
                server.getTickCount() + RESUME_OPEN_DELAY_TICKS, hold.heldOnShowdown()));
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, GraceHold>> it = HOLDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, GraceHold> entry = it.next();
            if (entry.getValue().deadlineTick() > now) continue;
            it.remove();
            RaidSession raid = findRaid(entry.getValue().raidId());
            // Safe even if the raid already ended for some other reason while this was pending:
            // onPlayerDisconnected's own guard requires Status.ACTIVE and no-ops otherwise.
            RaidLifecycleCoordinator.onPlayerDisconnected(raid, entry.getKey());
        }
        for (Iterator<Map.Entry<UUID, PendingResume>> it = RESUMES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PendingResume> entry = it.next();
            if (entry.getValue().readyTick() > now) continue;
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            // They disconnected again before the delay elapsed; the next real disconnect/join pair
            // will queue its own resume, so this stale one is simply dropped.
            if (player == null) continue;
            resumeBattleUi(findRaid(entry.getValue().raidId()), player);
        }
    }

    /**
     * False only when every active participant is currently mid-grace -- a solo disconnect, or the
     * last player standing in a multiplayer raid. {@link RaidCombatRuleService} reads this to stop
     * advancing the raid's own time-limit clock in exactly that case, so the clock cannot expire out
     * from under a player who is only just reconnecting. A raid this doesn't know about (not
     * currently mid-grace at all) always reports true, so it never affects the common case.
     */
    public static boolean hasActivePresence(RaidSession raid) {
        if (raid == null) return true;
        for (UUID id : raid.getActiveParticipants()) {
            if (!HOLDS.containsKey(id)) return true;
        }
        return false;
    }

    private static void resumeBattleUi(RaidSession raid, ServerPlayer player) {
        if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE) return;
        PokemonBattle battle = raid.getBattle();
        if (battle.getEnded()) return;
        BattleActor actor = battle.getActor(player);
        if (actor == null) return;

        // Mirrors SpectateBattleHandler's own resend-to-a-specific-player pattern, with this actor's
        // real BattleSide rather than a spectator's null one, so the client draws its own controls.
        CobblemonNetwork.INSTANCE.sendPacket(player, new BattleInitializePacket(battle, actor.getSide()));
        CobblemonNetwork.INSTANCE.sendPacket(player, new BattleMessagePacket(battle.getChatLog()));
        // Whatever request they were last sent (only possible for the never-held solo/last-one-out
        // case, since a held side's request is a synthetic "wait" the raid patch keeps overwriting)
        // never reached this connection, so it needs resending -- the same packet
        // RequestInstruction itself sends whenever Showdown emits a fresh |request|.
        if (actor.getRequest() != null) {
            actor.sendUpdate(new BattleQueueRequestPacket(actor.getRequest()));
        }
        if (actor.getMustChoose()) {
            actor.sendUpdate(new BattleMakeChoicePacket());
        }
    }

    private static void announceOthers(MinecraftServer server, RaidSession raid, UUID exclude, Component message) {
        for (UUID id : raid.getActiveParticipants()) {
            if (id.equals(exclude)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(message);
        }
    }

    private static RaidSession findRaid(UUID raidId) {
        if (raidId == null) return null;
        for (RaidSession raid : RaidRegistry.all()) {
            if (raid.getId().equals(raidId)) return raid;
        }
        return null;
    }

    /** Static maps, same reason as every other tick-driven service: cleared per-world, not per-raid. */
    public static void onServerStopped() {
        HOLDS.clear();
        RESUMES.clear();
    }
}
