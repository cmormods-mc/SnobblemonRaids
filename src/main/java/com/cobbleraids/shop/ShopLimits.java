package com.cobbleraids.shop;

import com.google.gson.JsonObject;

/**
 * The catalogue-wide defaults for how often a thing may be bought.
 *
 * <p>Defaults rather than per-entry requirements, because a catalogue of two hundred listings that
 * each had to repeat "limit 5, reset daily" would be two hundred places for that to be typed wrong.
 * An entry that wants something else says so; everything else inherits.
 *
 * <p>Pokemon and items get separate defaults because they are different kinds of purchase. A
 * Pokemon is a thing you own -- one is the whole point of it. An item is stock, and a player
 * clearing out the Ultra Balls for the week in one sitting is what a limit is there to stop.
 */
public record ShopLimits(int pokemon, int item, ShopResetPeriod reset) {

    /** Nobody buys a thousand of anything in a day; past this the limit is doing no work. */
    public static final int MAX_LIMIT = 1000;

    public static final ShopLimits DEFAULTS = new ShopLimits(1, 5, ShopResetPeriod.DAILY);

    public ShopLimits {
        pokemon = clamp(pokemon);
        item = clamp(item);
        if (reset == null) reset = ShopResetPeriod.DAILY;
    }

    /** Zero means unlimited, so it survives clamping. */
    private static int clamp(int value) {
        return Math.max(0, Math.min(value, MAX_LIMIT));
    }

    public int defaultFor(boolean isPokemon) {
        return isPokemon ? pokemon : item;
    }

    public static ShopLimits fromJson(JsonObject root) {
        if (root == null || !root.has("limits") || !root.get("limits").isJsonObject()) return DEFAULTS;
        JsonObject limits = root.getAsJsonObject("limits");
        return new ShopLimits(
                limits.has("pokemon") ? limits.get("pokemon").getAsInt() : DEFAULTS.pokemon(),
                limits.has("item") ? limits.get("item").getAsInt() : DEFAULTS.item(),
                ShopResetPeriod.parse(limits.has("reset") ? limits.get("reset").getAsString() : null,
                        DEFAULTS.reset()));
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("pokemon", pokemon);
        root.addProperty("item", item);
        root.addProperty("reset", reset.serializedName());
        return root;
    }
}
