package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.ShopActionPayload;
import com.cobbleraids.network.ShopEntryPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.shop.RaidShopGateway;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * The raid shop as a web page: the same server logic as the native screen, a different view.
 *
 * <p>Nothing here decides anything. A click arrives as {@code {action, page, id}} and is handed to
 * {@link RaidShopGateway#handle}, which validates it exactly as it does a native packet; the page it
 * sends back is turned into JSON here instead of a payload.
 *
 * <p>WebGUI caps one event at 32 KB, so icons do not ride with the page. Each is sent once per open
 * in its own small batch and cached by the page, and a page message carries only their keys.
 */
public final class ShopWebPage {
    private ShopWebPage() {}

    public static final String CHANNEL = "cobbleraids.shop";
    public static final String PAGE = "shop/shop.html";
    /** Comfortably under WebGUI's 32 KB event limit once JSON escaping is counted. */
    private static final int ICON_BATCH_CHARS = 24_000;

    /** Icon keys each player's open page already holds, so a key is sent once per open. */
    private static final Map<UUID, Set<String>> SENT = new ConcurrentHashMap<>();

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("shop-web-event", () -> onEvent(player, json)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                RaidFaultBarrier.guard("player-disconnect:shop-web-icons",
                        () -> SENT.remove(handler.getPlayer().getUUID())));
    }

    /** Opens the page; it asks for its first page itself once it is listening. */
    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message;
        try {
            message = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return;
        }
        String action = string(message, "action");
        int page = 0;
        if (message.has("page") && message.get("page").isJsonPrimitive()) {
            try {
                page = message.get("page").getAsInt();
            } catch (NumberFormatException e) {
                return;
            }
        }
        switch (action) {
            case "ready" -> {
                SENT.put(player.getUUID(), new HashSet<>());
                RaidShopGateway.handle(player, ShopActionPayload.turnTo(0));
            }
            case "turn" -> RaidShopGateway.handle(player, ShopActionPayload.turnTo(page));
            case "buy" -> {
                String id = string(message, "id");
                if (!id.isEmpty() && id.length() <= ShopActionPayload.MAX_ID_LENGTH) {
                    RaidShopGateway.handle(player, ShopActionPayload.buy(page, id));
                }
            }
            default -> RaidLog.warn("Ignoring unknown shop page action '{}' from {}", action,
                    player.getGameProfile().getName());
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** Sends a page, then whichever of its icons this open of the page has not been given yet. */
    public static void deliver(ServerPlayer player, ShopPagePayload payload) {
        Set<String> sent = SENT.computeIfAbsent(player.getUUID(), id -> new HashSet<>());
        JsonObject page = new JsonObject();
        page.addProperty("heading", payload.heading());
        page.addProperty("pageIndex", payload.pageIndex());
        page.addProperty("pageCount", payload.pageCount());
        page.addProperty("balance", payload.balance());
        JsonArray entries = new JsonArray();
        Set<String> wanted = new HashSet<>();
        for (ShopEntryPayload entry : payload.entries()) {
            JsonObject cell = new JsonObject();
            cell.addProperty("id", entry.id());
            cell.addProperty("cost", entry.cost());
            cell.addProperty("pokemon", entry.pokemon());
            cell.addProperty("count", entry.count());
            cell.addProperty("level", entry.level());
            cell.addProperty("shiny", entry.shiny());
            cell.addProperty("remaining", entry.limit() > 0 ? Math.max(0, entry.remaining()) : 0);
            cell.addProperty("limit", entry.limit());
            cell.addProperty("soldOut", entry.soldOut());
            cell.addProperty("personal", entry.personal());
            cell.addProperty("canReroll", entry.canReroll());
            cell.addProperty("rerollCost", entry.rerollCost());
            cell.addProperty("ivPercent", entry.ivPercent());
            cell.addProperty("evPercent", entry.evPercent());
            String key;
            if (entry.pokemon()) {
                cell.addProperty("species", entry.species());
                key = WebIcons.speciesKey(entry.species(), entry.shiny());
            } else {
                String namespace = entry.itemId().getNamespace();
                String path = entry.itemId().getPath();
                cell.addProperty("name", WebIcons.itemName(namespace, path));
                key = WebIcons.itemKey(namespace, path);
            }
            if (WebIcons.dataUrl(key).isEmpty()) key = WebIcons.FALLBACK_ITEM;
            cell.addProperty("icon", key);
            wanted.add(key);
            entries.add(cell);
        }
        page.add("entries", entries);

        // Icons first: the page paints from its cache when the page message lands.
        wanted.removeAll(sent);
        sendIcons(player, wanted);
        sent.addAll(wanted);
        WebGuiBridge.emit(player, "shop_page", page.toString());
    }

    private static void sendIcons(ServerPlayer player, Set<String> keys) {
        JsonObject batch = new JsonObject();
        int size = 0;
        for (String key : keys) {
            String url = WebIcons.dataUrl(key).orElse(null);
            if (url == null) continue;
            if (size > 0 && size + url.length() > ICON_BATCH_CHARS) {
                WebGuiBridge.emit(player, "shop_icons", batch.toString());
                batch = new JsonObject();
                size = 0;
            }
            batch.addProperty(key, url);
            size += url.length() + key.length();
        }
        if (size > 0) WebGuiBridge.emit(player, "shop_icons", batch.toString());
    }
}
