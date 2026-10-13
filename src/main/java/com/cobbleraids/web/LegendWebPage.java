package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.LegendGalleryQuery;
import com.cobbleraids.catching.LegendRoomGateway;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.LegendActionPayload;
import com.cobbleraids.network.LegendEntryPayload;
import com.cobbleraids.network.LegendPagePayload;
import com.cobbleraids.renown.RenownBoon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Hall of Legends as a web page: the server's filter and pagination, shown as a list.
 * Same shape as {@link TrophyWebPage}; it needs no icons.
 */
public final class LegendWebPage {
    private LegendWebPage() {}

    public static final String CHANNEL = "cobbleraids.legends";
    public static final String PAGE = "legends/legends.html";
    /** Rows that fit the page's panel: the native screen's 360-pixel panel holds seven. */
    private static final int ROWS = 7;

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("legends-web-event", () -> onEvent(player, json)));
    }

    /** Opens the page; it asks for its first page itself once it is listening. */
    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        switch (WebJson.string(message, "action")) {
            case "ready" -> LegendRoomGateway.handle(player, LegendActionPayload.initial(ROWS));
            case "query" -> LegendRoomGateway.handle(player, new LegendActionPayload(
                    Math.max(0, WebJson.integer(message, "page")), ROWS,
                    message.has("mine") && message.get("mine").isJsonPrimitive() && message.get("mine").getAsBoolean(),
                    WebJson.bounded(message, "tier", LegendActionPayload.MAX_TIER_LENGTH)));
            default -> RaidLog.warn("Ignoring unknown legends page action from {}", player.getGameProfile().getName());
        }
    }

    public static void deliver(ServerPlayer player, LegendPagePayload payload) {
        JsonObject page = new JsonObject();
        page.addProperty("pageIndex", payload.pageIndex());
        page.addProperty("pageCount", payload.pageCount());
        page.addProperty("total", payload.total());
        page.addProperty("mineCount", payload.mineCount());
        page.add("tiers", WebTiers.all());
        JsonArray entries = new JsonArray();
        for (LegendEntryPayload entry : payload.entries()) {
            JsonObject row = new JsonObject();
            RaidRarityTier tier = WebTiers.parse(entry.tier());
            row.addProperty("title", entry.title());
            row.addProperty("species", entry.species());
            row.addProperty("tier", entry.tier());
            row.addProperty("tierName", tier == null ? entry.tier() : tier.displayName());
            row.addProperty("tierColor", tier == null ? "#39f2ff" : WebTiers.color(tier));
            row.addProperty("boon", boonLabel(entry.boon()));
            row.addProperty("defeatedAt", entry.defeatedAtEpochMs());
            JsonArray victors = new JsonArray();
            entry.victorNames().forEach(victors::add);
            row.add("victors", victors);
            entries.add(row);
        }
        page.add("entries", entries);
        WebGuiBridge.emit(player, "legends_page", page.toString());
    }

    private static String boonLabel(String encoded) {
        return RenownBoon.decode(encoded).map(boon -> switch (boon.kind()) {
            case NONE -> "No Boon";
            case HP_POOL -> "HP Pool";
            case STAT_FOCUS -> capitalizeWords(boon.stat().replace('_', ' ')) + " Focus";
        }).orElse("Unknown Boon");
    }

    private static String capitalizeWords(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
