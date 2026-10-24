package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.ShopActionPayload;
import com.cobbleraids.network.ShopEntryPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.shop.RaidShopGateway;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/**
 * The raid shop as a web page: the same server logic as the native screen, a different view.
 *
 * <p>Nothing here decides anything. A click arrives as {@code {action, page, id}} and is handed to
 * {@link RaidShopGateway#handle}, which validates it exactly as it does a native packet; the page it
 * sends back is turned into JSON here instead of a payload.
 *
 * <p>WebGUI caps one event at 32 KB, so icons do not ride with the page; see {@link WebIconFeed}.
 */
public final class ShopWebPage {
    private ShopWebPage() {}

    public static final String CHANNEL = "cobbleraids.shop";
    public static final String PAGE = "shop/shop.html";

    private static final WebIconFeed ICONS = new WebIconFeed("shop_icons");

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("shop-web-event", () -> onEvent(player, json)));
    }

    /** Opens the page; it asks for its first page itself once it is listening. */
    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        int page = WebJson.integer(message, "page");
        switch (WebJson.string(message, "action")) {
            case "ready" -> {
                ICONS.reset(player.getUUID());
                RaidShopGateway.handle(player, ShopActionPayload.turnTo(0));
            }
            case "turn" -> RaidShopGateway.handle(player, ShopActionPayload.turnTo(page));
            case "buy" -> {
                String id = WebJson.string(message, "id");
                if (!id.isEmpty() && id.length() <= ShopActionPayload.MAX_ID_LENGTH) {
                    RaidShopGateway.handle(player, ShopActionPayload.buy(page, id));
                }
            }
            default -> RaidLog.warn("Ignoring unknown shop page action from {}", player.getGameProfile().getName());
        }
    }

    /** Sends a page, preceded by whichever of its icons this open of the page has not been given yet. */
    public static void deliver(ServerPlayer player, ShopPagePayload payload) {
        JsonObject page = new JsonObject();
        page.addProperty("heading", payload.heading());
        page.addProperty("pageIndex", payload.pageIndex());
        page.addProperty("pageCount", payload.pageCount());
        page.addProperty("balance", payload.balance());
        page.addProperty("rotatesInMillis", payload.rotatesInMillis());
        JsonArray entries = new JsonArray();
        Set<String> wanted = new LinkedHashSet<>();
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
        ICONS.send(player, wanted);
        WebGuiBridge.emit(player, "shop_page", page.toString());
    }
}
