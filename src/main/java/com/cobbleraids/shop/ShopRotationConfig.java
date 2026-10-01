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
 * @param minPrice the floor, so a level-25 Rattata is not nearly free
 * @param excludedLabels Cobblemon species labels that are never offered
 * @param starterBasePrice what a first-stage starter (Charmander) costs, at any level
 * @param starterMiddlePrice what a second-stage starter (Charmeleon) costs, at any level
 * @param starterFinalPrice what a fully evolved starter (Charizard) costs, at any level; each is also
 *                          held to {@code maxPrice}, so lowering that still caps the whole page
 */
public record ShopRotationConfig(boolean enabled, int listings, int minLevel, int maxLevel,
                                 int minPrice, int maxPrice, List<String> excludedLabels,
                                 int starterBasePrice, int starterMiddlePrice, int starterFinalPrice) {

    public static final int MAX_LISTINGS = 36;

    /** What shipped before the 25-50 / 500 retune. An untouched value moves; an operator's own stays. */
    static final int PREVIOUS_MIN_LEVEL = 5;
    static final int PREVIOUS_MIN_PRICE = 25;
    static final int PREVIOUS_MAX_PRICE = 1250;

    /**
     * Levels 25-50, nothing over 500 Raid Points, nothing under 100. Legendary and mythical are
     * excluded by request; the rest are the other labels that mark the same kind of species. A starter
     * line costs 250 / 375 / 500 by stage, flat, so the fully evolved ones sit at the ceiling.
     */
    public static final ShopRotationConfig DEFAULTS = new ShopRotationConfig(true, 10, 25, 50, 100, 500,
            List.of("legendary", "mythical", "ultra_beast", "paradox", "restricted"), 250, 375, 500);

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
        for (int starter : new int[] {starterBasePrice, starterMiddlePrice, starterFinalPrice}) {
            if (starter < 0 || starter > ShopEntry.MAX_COST) {
                throw new IllegalArgumentException("rotation starter prices must be within 0.." + ShopEntry.MAX_COST);
            }
        }
        excludedLabels = List.copyOf(excludedLabels == null ? List.of() : excludedLabels);
    }

    /**
     * The flat price of a starter-line species, or empty for any other. Held to {@code maxPrice}
     * because that is the one number that promises nothing on the page costs more.
     */
    public java.util.OptionalInt starterPriceFor(String speciesId) {
        return ShopStarterLines.stageOf(speciesId).map(stage -> java.util.OptionalInt.of(Math.min(maxPrice, switch (stage) {
            case BASE -> starterBasePrice;
            case MIDDLE -> starterMiddlePrice;
            case FINAL -> starterFinalPrice;
        }))).orElse(java.util.OptionalInt.empty());
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
            int minLevel = block.has("min_level") ? block.get("min_level").getAsInt() : DEFAULTS.minLevel();
            int maxLevel = block.has("max_level") ? block.get("max_level").getAsInt() : DEFAULTS.maxLevel();
            int minPrice = block.has("min_price") ? block.get("min_price").getAsInt() : DEFAULTS.minPrice();
            int maxPrice = block.has("max_price") ? block.get("max_price").getAsInt() : DEFAULTS.maxPrice();
            // A value still at the old shipped default was never tuned, so it moves to the new one. Only
            // if the result is still a valid block, though: an operator who set their own max_level of 20
            // must not lose the whole block because min_level quietly jumped past it.
            if (maxPrice == PREVIOUS_MAX_PRICE) maxPrice = DEFAULTS.maxPrice();
            if (minPrice == PREVIOUS_MIN_PRICE && DEFAULTS.minPrice() <= maxPrice) minPrice = DEFAULTS.minPrice();
            if (minLevel == PREVIOUS_MIN_LEVEL && DEFAULTS.minLevel() <= maxLevel) minLevel = DEFAULTS.minLevel();
            JsonObject starters = block.has("starter_prices") && block.get("starter_prices").isJsonObject()
                    ? block.getAsJsonObject("starter_prices") : new JsonObject();
            return new ShopRotationConfig(
                    block.has("enabled") ? block.get("enabled").getAsBoolean() : DEFAULTS.enabled(),
                    block.has("listings") ? block.get("listings").getAsInt() : DEFAULTS.listings(),
                    minLevel, maxLevel, minPrice, maxPrice, labels,
                    starters.has("base") ? starters.get("base").getAsInt() : DEFAULTS.starterBasePrice(),
                    starters.has("middle") ? starters.get("middle").getAsInt() : DEFAULTS.starterMiddlePrice(),
                    starters.has("final") ? starters.get("final").getAsInt() : DEFAULTS.starterFinalPrice());
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
        JsonObject starters = new JsonObject();
        starters.addProperty("base", starterBasePrice);
        starters.addProperty("middle", starterMiddlePrice);
        starters.addProperty("final", starterFinalPrice);
        block.add("starter_prices", starters);
        return block;
    }
}
