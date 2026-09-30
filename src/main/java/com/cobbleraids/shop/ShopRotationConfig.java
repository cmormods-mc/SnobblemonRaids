package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rotating Pokemon page: how many listings, what levels they can be, what the dearest one
 * costs, and which species are never offered.
 *
 * <p>Lives in shop.json beside the prices it feeds, for the reason the rest of the catalogue does.
 * The four-hour window is deliberately not a setting: it is {@link ShopResetPeriod#ROTATION}, which
 * the purchase tallies are keyed on, so changing it here would desynchronise the listings from the
 * per-player limits that are supposed to reset with them.
 *
 * <p>Free of Minecraft and Cobblemon types, so the rotation can be unit-tested whole.
 *
 * @param maxPrice the price of the hardest-to-acquire species at {@code maxLevel}; nothing on the
 *                 page can cost more, which is the one number an operator is actually choosing
 * @param minPrice the floor, so a level-5 Rattata is not free
 * @param excludedLabels Cobblemon species labels that are never offered
 */
public record ShopRotationConfig(boolean enabled, int listings, int minLevel, int maxLevel,
                                 int minPrice, int maxPrice, List<String> excludedLabels) {

    public static final int MAX_LISTINGS = 36;

    /** Legendary and mythical by request; the rest are the other labels that mark the same kind of species. */
    public static final ShopRotationConfig DEFAULTS = new ShopRotationConfig(true, 10, 5, 50, 25, 1250,
            List.of("legendary", "mythical", "ultra_beast", "paradox", "restricted"));

    public ShopRotationConfig {
        if (listings < 1 || listings > MAX_LISTINGS) {
            throw new IllegalArgumentException("rotation.listings must be 1.." + MAX_LISTINGS);
        }
        if (minLevel < 1 || maxLevel > ShopPokemonGift.MAX_LEVEL || minLevel > maxLevel) {
            throw new IllegalArgumentException("rotation levels must satisfy 1 <= min_level <= max_level <= "
                    + ShopPokemonGift.MAX_LEVEL);
        }
        if (minPrice < 0 || maxPrice > ShopEntry.MAX_COST || minPrice > maxPrice) {
            throw new IllegalArgumentException("rotation prices must satisfy 0 <= min_price <= max_price <= "
                    + ShopEntry.MAX_COST);
        }
        excludedLabels = List.copyOf(excludedLabels == null ? List.of() : excludedLabels);
    }

    /** A bad block costs the rotation its settings, not the operator the whole shop. */
    public static ShopRotationConfig fromJson(JsonObject root) {
        if (root == null || !root.has("rotation") || !root.get("rotation").isJsonObject()) return DEFAULTS;
        JsonObject block = root.getAsJsonObject("rotation");
        try {
            List<String> labels = new ArrayList<>();
            if (block.has("excluded_labels") && block.get("excluded_labels").isJsonArray()) {
                for (JsonElement element : block.getAsJsonArray("excluded_labels")) {
                    labels.add(element.getAsString().trim().toLowerCase(Locale.ROOT));
                }
            } else {
                labels = DEFAULTS.excludedLabels();
            }
            return new ShopRotationConfig(
                    block.has("enabled") ? block.get("enabled").getAsBoolean() : DEFAULTS.enabled(),
                    block.has("listings") ? block.get("listings").getAsInt() : DEFAULTS.listings(),
                    block.has("min_level") ? block.get("min_level").getAsInt() : DEFAULTS.minLevel(),
                    block.has("max_level") ? block.get("max_level").getAsInt() : DEFAULTS.maxLevel(),
                    block.has("min_price") ? block.get("min_price").getAsInt() : DEFAULTS.minPrice(),
                    block.has("max_price") ? block.get("max_price").getAsInt() : DEFAULTS.maxPrice(),
                    labels);
        } catch (RuntimeException ex) {
            RaidLog.error("shop catalogue: the rotation block is unusable (" + ex.getMessage()
                    + "); using the defaults");
            return DEFAULTS;
        }
    }

    public JsonObject toJson() {
        JsonObject block = new JsonObject();
        block.addProperty("enabled", enabled);
        block.addProperty("listings", listings);
        block.addProperty("min_level", minLevel);
        block.addProperty("max_level", maxLevel);
        block.addProperty("min_price", minPrice);
        block.addProperty("max_price", maxPrice);
        JsonArray labels = new JsonArray();
        excludedLabels.forEach(labels::add);
        block.add("excluded_labels", labels);
        return block;
    }
}
