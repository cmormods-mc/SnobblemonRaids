package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonObject;
import java.util.Locale;

/** An item a shop entry hands over: a registry id and how many of it. */
public record ShopItemGift(String itemId, int count) {

    /** A stack, so a single purchase cannot quietly hand over more than an inventory slot holds. */
    public static final int MAX_COUNT = 64;

    public ShopItemGift {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("item id is required");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count " + count + " is outside 1.." + MAX_COUNT);
        }
    }

    /** Null when the block is unusable, having said why. */
    static ShopItemGift fromJson(JsonObject root, String entryId) {
        String itemId = root.has("item") ? root.get("item").getAsString().trim().toLowerCase(Locale.ROOT) : null;
        if (itemId == null || itemId.isBlank()) {
            RaidLog.error("shop entry " + entryId + ": item id is missing");
            return null;
        }
        // A bare path is the mistake an operator makes first, and Minecraft resolves it to
        // minecraft: rather than failing -- which is how a shop ends up selling the wrong thing.
        if (!itemId.contains(":")) {
            RaidLog.error("shop entry " + entryId + ": item " + itemId
                    + " has no namespace; write it as cobblemon:" + itemId);
            return null;
        }
        int count = root.has("count") ? root.get("count").getAsInt() : 1;
        if (count < 1 || count > MAX_COUNT) {
            RaidLog.error("shop entry " + entryId + ": count " + count + " is outside 1.." + MAX_COUNT);
            return null;
        }
        return new ShopItemGift(itemId, count);
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("item", itemId);
        root.addProperty("count", count);
        return root;
    }
}
