package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidBossTraits;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Pokemon a shop entry hands over, fixed entirely by the operator.
 *
 * <p>Nothing here is rolled. Two players paying the same points get the same Pokemon, which is the
 * only way a price on one means anything -- a rolled nature or IV spread makes the good outcome the
 * reason to buy again, and turns a priced shop into a slot machine nobody priced.
 *
 * <p>Deliberately <em>not</em> {@link RaidBossTraits}, despite the overlap. That type refuses held
 * items such as Focus Sash and Life Orb, because a raid boss's simulated HP is pinned by the battle
 * patch and those items read or write it. None of that is true of a Pokemon sitting in a player's
 * party, so reusing it here would silently strip legitimate held items from a purchase. The stat
 * block is still read through its reader, so what a stat is called has exactly one owner.
 *
 * <p>Free of Cobblemon types: every value is a plain string, resolved against the registries only
 * at the moment of granting. That keeps the catalogue loadable and testable without a server.
 */
public record ShopPokemonGift(
        String species,
        int level,
        boolean shiny,
        String nature,
        String ability,
        String gender,
        String form,
        String teraType,
        String heldItem,
        Map<String, Integer> ivs,
        Map<String, Integer> evs
) {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 100;

    public ShopPokemonGift {
        if (species == null || species.isBlank()) {
            throw new IllegalArgumentException("species is required");
        }
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level " + level + " is outside " + MIN_LEVEL + ".." + MAX_LEVEL);
        }
        ivs = Map.copyOf(ivs == null ? Map.of() : ivs);
        evs = Map.copyOf(evs == null ? Map.of() : evs);
    }

    /** Null when the block is unusable, having said why. */
    static ShopPokemonGift fromJson(JsonObject root, String entryId) {
        String species = text(root, "species");
        if (species == null) {
            RaidLog.error("shop entry " + entryId + ": species is missing");
            return null;
        }
        int level = root.has("level") ? root.get("level").getAsInt() : MAX_LEVEL;
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            RaidLog.error("shop entry " + entryId + ": level " + level
                    + " is outside " + MIN_LEVEL + ".." + MAX_LEVEL);
            return null;
        }
        JsonObject traits = root.has("traits") && root.get("traits").isJsonObject()
                ? root.getAsJsonObject("traits")
                : new JsonObject();
        String context = "shop entry " + entryId;
        return new ShopPokemonGift(
                species,
                level,
                root.has("shiny") && root.get("shiny").getAsBoolean(),
                text(traits, "nature"),
                text(traits, "ability"),
                text(traits, "gender"),
                text(traits, "form"),
                text(traits, "tera_type"),
                text(traits, "held_item"),
                RaidBossTraits.readStats(traits, "ivs", 0, 31, context),
                RaidBossTraits.readStats(traits, "evs", 0, 252, context));
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("species", species);
        root.addProperty("level", level);
        if (shiny) root.addProperty("shiny", true);
        JsonObject traits = new JsonObject();
        put(traits, "nature", nature);
        put(traits, "ability", ability);
        put(traits, "gender", gender);
        put(traits, "form", form);
        put(traits, "tera_type", teraType);
        put(traits, "held_item", heldItem);
        putStats(traits, "ivs", ivs);
        putStats(traits, "evs", evs);
        if (!traits.isEmpty()) root.add("traits", traits);
        return root;
    }

    private static void put(JsonObject object, String key, String value) {
        if (value != null && !value.isBlank()) object.addProperty(key, value);
    }

    private static void putStats(JsonObject object, String key, Map<String, Integer> stats) {
        if (stats.isEmpty()) return;
        JsonObject block = new JsonObject();
        // Sorted, so writing the file back is byte-stable and the migration does not rewrite a
        // catalogue nobody edited.
        new TreeMap<>(stats).forEach(block::addProperty);
        object.add(key, block);
    }

    private static String text(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        String value = object.get(key).getAsString().trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    /** What the screen labels the cell with. */
    public String displayName() {
        String name = species.substring(0, 1).toUpperCase(Locale.ROOT) + species.substring(1);
        return shiny ? "Shiny " + name : name;
    }
}
