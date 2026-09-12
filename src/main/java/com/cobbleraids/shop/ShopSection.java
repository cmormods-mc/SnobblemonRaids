package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A named run of entries -- the shop's equivalent of a shelf.
 *
 * <p>Sections exist because the frame's header names the page, and because a flat list of a few
 * hundred entries is a catalogue nobody can find anything in. Each section starts on its own page,
 * so an operator adding one item to the first section does not shift every later page by one and
 * change what the arrows land on.
 */
public record ShopSection(String id, String title, List<ShopEntry> entries) {

    public ShopSection {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("section id is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("section title is required");
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    /** Null when the section is unusable. Entries that fail are dropped individually. */
    static ShopSection fromJson(JsonObject root, ShopLimits defaults) {
        String id = root.has("id") ? root.get("id").getAsString().trim().toLowerCase(Locale.ROOT) : null;
        if (id == null || id.isBlank()) {
            RaidLog.error("shop catalogue: dropping a section with no id");
            return null;
        }
        String title = root.has("title") ? root.get("title").getAsString().trim() : "";
        if (title.isBlank()) title = id;

        List<ShopEntry> entries = new ArrayList<>();
        JsonArray array = root.has("entries") && root.get("entries").isJsonArray()
                ? root.getAsJsonArray("entries")
                : new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                RaidLog.error("shop section " + id + ": dropping an entry that is not an object");
                continue;
            }
            ShopEntry entry = ShopEntry.fromJson(element.getAsJsonObject(), defaults);
            if (entry != null) entries.add(entry);
        }
        return new ShopSection(id, title, entries);
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("title", title);
        JsonArray array = new JsonArray();
        entries.forEach(entry -> array.add(entry.toJson()));
        root.add("entries", array);
        return root;
    }
}
