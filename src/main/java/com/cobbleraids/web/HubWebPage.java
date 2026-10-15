package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.HallOfLegends;
import com.cobbleraids.catching.LegendGalleryQuery;
import com.cobbleraids.catching.LegendRoomGateway;
import com.cobbleraids.catching.TrophyGalleryQuery;
import com.cobbleraids.catching.TrophyLedger;
import com.cobbleraids.catching.TrophyRoomGateway;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.reward.points.RaidPointsStore;
import com.cobbleraids.shop.RaidShopGateway;
import com.cobbleraids.stats.PlayerStats;
import com.cobbleraids.stats.RaidStats;
import com.cobbleraids.stats.RaidStatsStore;
import com.cobbleraids.stats.StatScope;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The raid hub: one page with a door to each of the others. It is what the main-menu key (F6 by
 * default) opens, so every web screen is reachable without remembering a command.
 *
 * <p>The hub shows a figure or two from each room and sends nothing but "open this one"; opening goes
 * through the same gateway {@code /cobbleraids shop|trophy|legends} uses.
 */
public final class HubWebPage {
    private HubWebPage() {}

    public static final String CHANNEL = "cobbleraids.hub";
    public static final String PAGE = "hub/hub.html";

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("hub-web-event", () -> onEvent(player, json)));
        // Point the main-menu key at the hub, unless the operator has chosen a page of their own in
        // WebGUI's config. Sent about a second after joining, which puts it after WebGUI's own join
        // message and after the client's channel list has settled.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RaidFaultBarrier.guard("player-join:web-hub", () -> MENU_DELAY.put(handler.getPlayer().getUUID(), MENU_DELAY_TICKS)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                RaidFaultBarrier.guard("player-disconnect:web-hub", () -> MENU_DELAY.remove(handler.getPlayer().getUUID())));
        ServerTickEvents.END_SERVER_TICK.register(server ->
                RaidFaultBarrier.guard("tick:web-hub", () -> sendDueMenus(server)));
    }

    /** Players whose main-menu page is still to be sent, with the ticks left. Empty on almost every tick. */
    private static final Map<UUID, Integer> MENU_DELAY = new ConcurrentHashMap<>();
    private static final int MENU_DELAY_TICKS = 20;

    private static void sendDueMenus(MinecraftServer server) {
        if (MENU_DELAY.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Integer>> it = MENU_DELAY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && WebUi.canShow(player) && WebGuiBridge.configuredMainMenuUrl().isBlank()) {
                WebGuiBridge.sendMainMenuUrl(player, WebUi.pageUrl(PAGE));
            }
        }
    }

    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        switch (WebJson.string(message, "action")) {
            case "ready" -> WebGuiBridge.emit(player, "hub_state", state(player).toString());
            case "open" -> {
                switch (WebJson.string(message, "target")) {
                    case "shop" -> RaidShopGateway.open(player);
                    case "trophy" -> TrophyRoomGateway.open(player, TrophyGalleryQuery.MAX_COLUMNS);
                    case "legends" -> LegendRoomGateway.open(player, LegendGalleryQuery.MAX_COLUMNS);
                    case "leaders" -> LeaderboardWebPage.open(player);
                    default -> { }
                }
            }
            default -> RaidLog.warn("Ignoring unknown hub page action from {}", player.getGameProfile().getName());
        }
    }

    private static JsonObject state(ServerPlayer player) {
        JsonObject out = new JsonObject();
        out.addProperty("name", player.getGameProfile().getName());
        out.addProperty("balance", RaidPointsStore.balance(player.getUUID()));
        out.addProperty("trophies", TrophyLedger.forPlayer(player.getUUID()).size());
        out.addProperty("legends", HallOfLegends.all().size());
        PlayerStats stats = RaidStatsStore.book().players().get(player.getUUID());
        out.addProperty("raidsWon", stats == null ? 0 : stats.block(StatScope.ALL_TIME, RaidStats.window()).raidsWon());
        return out;
    }
}
