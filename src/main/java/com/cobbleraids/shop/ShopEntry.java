package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.RaidPurchaseTally;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One thing the shop sells: an id, a price in Raid Points, a purchase limit, and exactly one
 * payload.
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
public record ShopEntry(
        String id,
        int cost,
        int limit,
        ShopResetPeriod reset,
        ShopItemGift item,
        ShopPokemonGift pokemon
) {
    /** Lowercase, and nothing that could be mistaken for formatting or a path separator. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** A price nobody can reach is a broken listing, not a rare one. */
    public static final int MAX_COST = 1_000_000;

    /** A limit of zero is no limit at all. */
    public static final int UNLIMITED = 0;

    public ShopEntry {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("shop entry id must match " + ID.pattern() + ": " + id);
        }
        if (cost < 0 || cost > MAX_COST) {
            throw new IllegalArgumentException("cost " + cost + " is outside 0.." + MAX_COST);
        }
        if (limit < 0 || limit > ShopLimits.MAX_LIMIT) {
            throw new IllegalArgumentException("limit " + limit + " is outside 0.." + ShopLimits.MAX_LIMIT);
        }
        if (reset == null) reset = ShopResetPeriod.DAILY;
        if ((item == null) == (pokemon == null)) {
            throw new IllegalArgumentException("shop entry " + id + " must carry exactly one of item or pokemon");
        }
    }

    /** Takes the catalogue-wide defaults, which is what a listing that says nothing should get. */
    public static ShopEntry ofItem(String id, int cost, String itemId, int count) {
        return ofItem(id, cost, itemId, count, ShopLimits.DEFAULTS.item(), ShopLimits.DEFAULTS.reset());
    }

    public static ShopEntry ofPokemon(String id, int cost, ShopPokemonGift gift) {
        return ofPokemon(id, cost, gift, ShopLimits.DEFAULTS.pokemon(), ShopLimits.DEFAULTS.reset());
    }

    public static ShopEntry ofItem(String id, int cost, String itemId, int count, int limit,
                                   ShopResetPeriod reset) {
        return new ShopEntry(id, cost, limit, reset, new ShopItemGift(itemId, count), null);
    }

    public static ShopEntry ofPokemon(String id, int cost, ShopPokemonGift gift, int limit,
                                      ShopResetPeriod reset) {
        return new ShopEntry(id, cost, limit, reset, null, gift);
    }

    public boolean isPokemon() {
        return pokemon != null;
    }

    public boolean isLimited() {
        return limit > UNLIMITED;
    }

    /**
     * How many more of this the player may buy right now.
     *
     * <p>{@link Integer#MAX_VALUE} for an unlimited entry, so callers can compare without asking
     * whether a limit exists first.
     */
    public int remaining(RaidPurchaseTally tally, long today) {
        if (!isLimited()) return Integer.MAX_VALUE;
        RaidPurchaseTally actual = tally == null ? RaidPurchaseTally.NONE : tally;
        return Math.max(0, limit - actual.countOn(today, reset.resets()));
    }

    /** Null when the entry is unusable, having said why. One bad entry must not cost the shop. */
    static ShopEntry fromJson(JsonObject root, ShopLimits defaults) {
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

        boolean hasItem = root.has("item");
        boolean hasPokemon = root.has("species");
        if (hasItem == hasPokemon) {
            RaidLog.error("shop entry " + id + ": needs exactly one of item or species, and has "
                    + (hasItem ? "both" : "neither"));
            return null;
        }

        int limit = defaults.defaultFor(hasPokemon);
        ShopResetPeriod reset = defaults.reset();
        // Catalogues written before limits existed said once_per_player, which meant one for ever.
        // Read it so an operator's file keeps working, and so the meaning does not quietly change
        // from "one ever" to "one a day" underneath them.
        if (root.has("once_per_player") && root.get("once_per_player").getAsBoolean()) {
            limit = 1;
            reset = ShopResetPeriod.NEVER;
        }
        if (root.has("limit")) {
            int requested;
            try {
                requested = root.get("limit").getAsInt();
            } catch (RuntimeException ex) {
                RaidLog.error("shop entry " + id + ": limit is not a number");
                return null;
            }
            if (requested < 0 || requested > ShopLimits.MAX_LIMIT) {
                RaidLog.error("shop entry " + id + ": limit " + requested
                        + " is outside 0.." + ShopLimits.MAX_LIMIT);
                return null;
            }
            limit = requested;
        }
        if (root.has("reset")) {
            reset = ShopResetPeriod.parse(root.get("reset").getAsString(), reset);
        }

        if (hasItem) {
            ShopItemGift gift = ShopItemGift.fromJson(root, id);
            return gift == null ? null : new ShopEntry(id, cost, limit, reset, gift, null);
        }
        ShopPokemonGift gift = ShopPokemonGift.fromJson(root, id);
        return gift == null ? null : new ShopEntry(id, cost, limit, reset, null, gift);
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("cost", cost);
        root.addProperty("limit", limit);
        root.addProperty("reset", reset.serializedName());
        JsonObject payload = item != null ? item.toJson() : pokemon.toJson();
        payload.entrySet().forEach(field -> root.add(field.getKey(), field.getValue()));
        return root;
    }
}
