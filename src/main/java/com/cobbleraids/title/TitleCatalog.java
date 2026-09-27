package com.cobbleraids.title;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every trainer title an operator has configured, in the order players unlock them.
 *
 * <p>Free of Minecraft types, like {@link com.cobbleraids.shop.ShopCatalog}, so parsing and unlock
 * evaluation are both testable without a server.
 */
public record TitleCatalog(List<TitleDefinition> titles) {

    public TitleCatalog {
        titles = List.copyOf(titles == null ? List.of() : titles);
    }

    /**
     * A small, immediately useful starter set, so a fresh server has titles to earn on day one
     * instead of an empty file an operator has to populate before the feature does anything.
     */
    public static TitleCatalog defaults() {
        return new TitleCatalog(List.of(
                new TitleDefinition("novice_raider", "Novice Raider", "GRAY",
                        TitleUnlockType.RAIDS_WON, null, 1),
                new TitleDefinition("veteran_raider", "Veteran Raider", "AQUA",
                        TitleUnlockType.RAIDS_WON, null, 25),
                new TitleDefinition("champion_raider", "Champion Raider", "GOLD",
                        TitleUnlockType.RAIDS_WON, null, 100),
                new TitleDefinition("legend_slayer", "Legend Slayer", "LIGHT_PURPLE",
                        TitleUnlockType.TIER_WINS, RaidRarityTier.LEGENDARY, 10),
                new TitleDefinition("collector", "Collector", "GREEN",
                        TitleUnlockType.BOSSES_CAUGHT, null, 50)));
    }

    /**
     * Reads a catalogue, dropping what it cannot use rather than refusing the file -- see
     * {@link TitleDefinition#fromJson} and {@link com.cobbleraids.shop.ShopCatalog#fromJson} for why.
     */
    public static TitleCatalog fromJson(JsonObject root) {
        List<TitleDefinition> titles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray array = root.has("titles") && root.get("titles").isJsonArray()
                ? root.getAsJsonArray("titles") : new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                RaidLog.error("titles.json: dropping a title that is not an object");
                continue;
            }
            TitleDefinition title = TitleDefinition.fromJson(element.getAsJsonObject());
            if (title == null) continue;
            if (!seen.add(title.id())) {
                RaidLog.error("titles.json: dropping a second title called " + title.id());
                continue;
            }
            titles.add(title);
        }
        return new TitleCatalog(titles);
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        titles.forEach(title -> array.add(title.toJson()));
        root.add("titles", array);
        return root;
    }

    /** Every title by id, in file order -- the order a player's {@code /cobbleraids title list} shows. */
    public Map<String, TitleDefinition> byId() {
        Map<String, TitleDefinition> index = new LinkedHashMap<>();
        titles.forEach(title -> index.putIfAbsent(title.id(), title));
        return java.util.Collections.unmodifiableMap(index);
    }
}
