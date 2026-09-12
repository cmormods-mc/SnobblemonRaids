package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One thing the shop sells: an id, a price in Raid Points, and exactly one payload.
 *
 * <p>The id is what a click sends. Not the slot, not the page, and not the price -- the server
 * re-reads all three from the catalogue, so a client that lies about them buys nothing unusual.
 * That is also why the id's characters are restricted: it travels over the wire and lands in log
 * lines, and an id that can carry a newline or a section sign is an id that can forge both.
 *
 * <p>An entry carries an item or a Pokemon, never both and never neither. That is checked here
 * rather than trusted, because a half-built entry would otherwise charge a player and hand over
 * nothing.
 */
public record ShopEntry(String id, int cost, boolean oncePerPlayer, ShopItemGift item, ShopPokemonGift pokemon) {

    /** Lowercase, and nothing that could be mistaken for formatting or a path separator. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** A price nobody can reach is a broken listing, not a rare one. */
    public static final int MAX_COST = 1_000_000;

    public ShopEntry {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("shop entry id must match " + ID.pattern() + ": " + id);
        }
        if (cost < 0 || cost > MAX_COST) {
            throw new IllegalArgumentException("cost " + cost + " is outside 0.." + MAX_COST);
        }
        if ((item == null) == (pokemon == null)) {
            throw new IllegalArgumentException("shop entry " + id + " must carry exactly one of item or pokemon");
        }
    }

    public static ShopEntry ofItem(String id, int cost, String itemId, int count) {
        return new ShopEntry(id, cost, false, new ShopItemGift(itemId, count), null);
    }

    public static ShopEntry ofPokemon(String id, int cost, ShopPokemonGift gift, boolean oncePerPlayer) {
        return new ShopEntry(id, cost, oncePerPlayer, null, gift);
    }

    public boolean isPokemon() {
        return pokemon != null;
    }

    /** Null when the entry is unusable, having said why. One bad entry must not cost the shop. */
    static ShopEntry fromJson(JsonObject root) {
        String id = root.has("id") ? root.get("id").getAsString().trim().toLowerCase(Locale.ROOT) : null;
        if (id == null || !ID.matcher(id).matches()) {
            RaidLog.error("shop catalogue: dropping an entry whose id is missing or unusable: " + id);
            return null;
        }
        if (!root.has("cost")) {
            RaidLog.error("shop entry " + id + ": cost is missing");
            return null;
        }
        int cost;
        try {
            cost = root.get("cost").getAsInt();
        } catch (RuntimeException ex) {
            RaidLog.error("shop entry " + id + ": cost is not a number");
            return null;
        }
        if (cost < 0 || cost > MAX_COST) {
            RaidLog.error("shop entry " + id + ": cost " + cost + " is outside 0.." + MAX_COST);
            return null;
        }
        boolean oncePerPlayer = root.has("once_per_player") && root.get("once_per_player").getAsBoolean();

        boolean hasItem = root.has("item");
        boolean hasPokemon = root.has("species");
        if (hasItem == hasPokemon) {
            RaidLog.error("shop entry " + id + ": needs exactly one of item or species, and has "
                    + (hasItem ? "both" : "neither"));
            return null;
        }
        if (hasItem) {
            ShopItemGift gift = ShopItemGift.fromJson(root, id);
            return gift == null ? null : new ShopEntry(id, cost, oncePerPlayer, gift, null);
        }
        ShopPokemonGift gift = ShopPokemonGift.fromJson(root, id);
        return gift == null ? null : new ShopEntry(id, cost, oncePerPlayer, null, gift);
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("cost", cost);
        if (oncePerPlayer) root.addProperty("once_per_player", true);
        JsonObject payload = item != null ? item.toJson() : pokemon.toJson();
        payload.entrySet().forEach(field -> root.add(field.getKey(), field.getValue()));
        return root;
    }
}
