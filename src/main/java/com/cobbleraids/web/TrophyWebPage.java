package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.TrophyGalleryQuery;
import com.cobbleraids.catching.TrophyRoomGateway;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/**
 * The trophy room as a web page. Like {@link ShopWebPage}, a different view over the same server
 * logic: a request goes to {@link TrophyRoomGateway#handle}, which runs {@link TrophyGalleryQuery}
 * exactly as it does for the native screen, and the page it picks comes back as JSON.
 */
public final class TrophyWebPage {
    private TrophyWebPage() {}

    public static final String CHANNEL = "cobbleraids.trophy";
    public static final String PAGE = "trophy/trophy.html";
    /** The page always lays out three display cases, so it always asks for three. */
    private static final int COLUMNS = TrophyGalleryQuery.MAX_COLUMNS;

    private static final WebIconFeed ICONS = new WebIconFeed("trophy_icons");

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("trophy-web-event", () -> onEvent(player, json)));
    }

    /** Opens the page; it asks for its first page itself once it is listening. */
    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        switch (WebJson.string(message, "action")) {
            case "ready" -> {
                ICONS.reset(player.getUUID());
                TrophyRoomGateway.handle(player, TrophyRoomActionPayload.initial(COLUMNS));
            }
            case "query" -> TrophyRoomGateway.handle(player, new TrophyRoomActionPayload(
                    Math.max(0, WebJson.integer(message, "page")), COLUMNS,
                    WebJson.bounded(message, "query", TrophyRoomActionPayload.MAX_QUERY_LENGTH),
                    WebJson.bounded(message, "tier", TrophyRoomActionPayload.MAX_TIER_LENGTH),
                    WebJson.integer(message, "shiny"), WebJson.integer(message, "sort")));
            default -> RaidLog.warn("Ignoring unknown trophy page action from {}", player.getGameProfile().getName());
        }
    }

    /** Sends a page, preceded by whichever of its sprites this open of the page does not hold yet. */
    public static void deliver(ServerPlayer player, TrophyRoomPagePayload payload) {
        JsonObject page = new JsonObject();
        page.addProperty("pageIndex", payload.pageIndex());
        page.addProperty("pageCount", payload.pageCount());
        page.addProperty("total", payload.total());
        page.addProperty("shinyTotal", payload.shinyTotal());
        page.addProperty("filteredCount", payload.filteredCount());
        page.add("tiers", WebTiers.all());
        JsonArray entries = new JsonArray();
        Set<String> wanted = new LinkedHashSet<>();
        for (TrophyRoomEntryPayload entry : payload.entries()) {
            JsonObject cell = new JsonObject();
            cell.addProperty("species", entry.species());
            cell.addProperty("level", entry.level());
            cell.addProperty("shiny", entry.shiny());
            cell.addProperty("iv", entry.ivPercent());
            cell.addProperty("ev", entry.evPercent());
            RaidRarityTier tier = WebTiers.parse(entry.tier());
            cell.addProperty("tier", entry.tier());
            cell.addProperty("tierName", tier == null ? entry.tier() : tier.displayName());
            cell.addProperty("tierColor", tier == null ? "#39f2ff" : WebTiers.color(tier));
            cell.addProperty("firstDefeatedAt", entry.firstDefeatedAtEpochMs());
            cell.addProperty("times", entry.timesDefeated());
            String key = WebIcons.speciesKey(entry.species(), entry.shiny());
            boolean have = WebIcons.dataUrl(key).isPresent();
            cell.addProperty("icon", have ? key : "");
            if (have) wanted.add(key);
            entries.add(cell);
        }
        page.add("entries", entries);
        ICONS.send(player, wanted);
        WebGuiBridge.emit(player, "trophy_page", page.toString());
    }
}
