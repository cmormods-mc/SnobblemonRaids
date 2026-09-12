package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
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
 * Everything the raid shop sells, and how it is paged.
 *
 * <p>Its own file rather than a block inside server.json, for the reason the reward policy is:
 * this is the part an operator edits most and is most likely to get wrong, and a mistake in it
 * must not take the rest of the configuration down with it.
 *
 * <p>The shipped default deliberately lists only {@code minecraft:} and {@code cobblemon:} items.
 * Everything else in this pack arrives from an optional mod, and an id that does not resolve is not
 * a missing row -- Minecraft rejects the whole structure that names it. The reward tables learned
 * that the hard way; a catalogue has no reason to learn it twice.
 *
 * <p>Free of Minecraft types, so the whole catalogue -- parsing, paging and validation -- is
 * testable without a server.
 */
public record ShopCatalog(int version, int perPage, ShopLimits limits, List<ShopSection> sections) {

    /** Bumped when the schema changes in a way an older file cannot be read as. */
    public static final int CURRENT_VERSION = 2;

    /** The sliced art's grid is eight by eight, so a page can never usefully hold more. */
    public static final int MAX_PER_PAGE = 64;

    public ShopCatalog {
        if (perPage < 1 || perPage > MAX_PER_PAGE) {
            throw new IllegalArgumentException("per_page " + perPage + " is outside 1.." + MAX_PER_PAGE);
        }
        if (limits == null) limits = ShopLimits.DEFAULTS;
        sections = List.copyOf(sections == null ? List.of() : sections);
    }

    public static ShopCatalog defaults() {
        List<ShopSection> sections = List.of(
                new ShopSection("consumables", "Consumables", List.of(
                        ShopEntry.ofItem("poke_ball", 5, "cobblemon:poke_ball", 8),
                        ShopEntry.ofItem("great_ball", 12, "cobblemon:great_ball", 8),
                        ShopEntry.ofItem("ultra_ball", 25, "cobblemon:ultra_ball", 8),
                        ShopEntry.ofItem("heal_ball", 10, "cobblemon:heal_ball", 8),
                        ShopEntry.ofItem("revive", 20, "cobblemon:revive", 4),
                        ShopEntry.ofItem("max_revive", 60, "cobblemon:max_revive", 2),
                        ShopEntry.ofItem("max_potion", 30, "cobblemon:max_potion", 4),
                        ShopEntry.ofItem("full_restore", 45, "cobblemon:full_restore", 2),
                        ShopEntry.ofItem("rare_candy", 75, "cobblemon:rare_candy", 1),
                        ShopEntry.ofItem("exp_candy_l", 40, "cobblemon:exp_candy_l", 4),
                        ShopEntry.ofItem("exp_candy_xl", 90, "cobblemon:exp_candy_xl", 2))),
                new ShopSection("held_items", "Held Items", List.of(
                        ShopEntry.ofItem("leftovers", 150, "cobblemon:leftovers", 1),
                        ShopEntry.ofItem("choice_band", 175, "cobblemon:choice_band", 1),
                        ShopEntry.ofItem("choice_specs", 175, "cobblemon:choice_specs", 1),
                        ShopEntry.ofItem("choice_scarf", 175, "cobblemon:choice_scarf", 1),
                        ShopEntry.ofItem("life_orb", 200, "cobblemon:life_orb", 1),
                        ShopEntry.ofItem("focus_sash", 200, "cobblemon:focus_sash", 1),
                        ShopEntry.ofItem("assault_vest", 175, "cobblemon:assault_vest", 1),
                        ShopEntry.ofItem("rocky_helmet", 150, "cobblemon:rocky_helmet", 1),
                        ShopEntry.ofItem("lucky_egg", 250, "cobblemon:lucky_egg", 1),
                        ShopEntry.ofItem("exp_share", 200, "cobblemon:exp_share", 1))),
                new ShopSection("training", "Training", List.of(
                        ShopEntry.ofItem("ability_capsule", 300, "cobblemon:ability_capsule", 1),
                        ShopEntry.ofItem("ability_patch", 600, "cobblemon:ability_patch", 1),
                        ShopEntry.ofItem("pp_up", 100, "cobblemon:pp_up", 1),
                        ShopEntry.ofItem("pp_max", 350, "cobblemon:pp_max", 1),
                        ShopEntry.ofItem("protein", 80, "cobblemon:protein", 4),
                        ShopEntry.ofItem("calcium", 80, "cobblemon:calcium", 4),
                        ShopEntry.ofItem("destiny_knot", 250, "cobblemon:destiny_knot", 1),
                        ShopEntry.ofItem("everstone", 60, "cobblemon:everstone", 1),
                        ShopEntry.ofItem("link_cable", 120, "cobblemon:link_cable", 1),
                        ShopEntry.ofItem("mirror_herb", 200, "cobblemon:mirror_herb", 1))),
                new ShopSection("pokemon", "Pokemon", List.of(
                        // One worked example of the fixed-Pokemon shape, so an operator editing
                        // this file can see every field a purchase can pin rather than guess.
                        ShopEntry.ofPokemon("starter_dratini", 2500,
                                new ShopPokemonGift("dratini", 15, false, "adamant", null, null, null, null,
                                        null,
                                        Map.of("hp", 31, "attack", 31, "speed", 31),
                                        Map.of())))));
        return new ShopCatalog(CURRENT_VERSION, MAX_PER_PAGE, ShopLimits.DEFAULTS, sections);
    }

    /**
     * Reads a catalogue, dropping what it cannot use rather than refusing the file.
     *
     * <p>One mistyped item id costing an operator their whole shop is the failure mode worth
     * designing against here -- they would see an empty screen and no obvious reason for it.
     */
    public static ShopCatalog fromJson(JsonObject root) {
        int version = root.has("version") ? root.get("version").getAsInt() : CURRENT_VERSION;
        ShopLimits limits = ShopLimits.fromJson(root);
        int perPage = root.has("per_page") ? root.get("per_page").getAsInt() : MAX_PER_PAGE;
        if (perPage < 1 || perPage > MAX_PER_PAGE) {
            RaidLog.error("shop catalogue: per_page " + perPage + " is outside 1.." + MAX_PER_PAGE
                    + "; using " + MAX_PER_PAGE);
            perPage = MAX_PER_PAGE;
        }

        List<ShopSection> sections = new ArrayList<>();
        Set<String> seenSections = new HashSet<>();
        Set<String> seenEntries = new HashSet<>();
        JsonArray array = root.has("sections") && root.get("sections").isJsonArray()
                ? root.getAsJsonArray("sections")
                : new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                RaidLog.error("shop catalogue: dropping a section that is not an object");
                continue;
            }
            ShopSection section = ShopSection.fromJson(element.getAsJsonObject(), limits);
            if (section == null) continue;
            if (!seenSections.add(section.id())) {
                RaidLog.error("shop catalogue: dropping a second section called " + section.id());
                continue;
            }
            // Entry ids have to be unique across the whole catalogue, not just within a section:
            // a purchase names one, and two entries answering to it means the player gets whichever
            // the lookup happened to reach first.
            List<ShopEntry> kept = new ArrayList<>();
            for (ShopEntry entry : section.entries()) {
                if (seenEntries.add(entry.id())) {
                    kept.add(entry);
                } else {
                    RaidLog.error("shop catalogue: dropping a second entry called " + entry.id()
                            + " in section " + section.id());
                }
            }
            sections.add(new ShopSection(section.id(), section.title(), kept));
        }
        return new ShopCatalog(version, perPage, limits, sections);
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("per_page", perPage);
        root.add("limits", limits.toJson());
        JsonArray array = new JsonArray();
        sections.forEach(section -> array.add(section.toJson()));
        root.add("sections", array);
        return root;
    }

    /**
     * The catalogue flattened into the pages the arrows step through.
     *
     * <p>Each section starts a new page, so adding one item to the first shelf does not shift every
     * later page along and change what a player's remembered page number lands on.
     */
    public List<ShopPageView> pages() {
        List<ShopPageView> pages = new ArrayList<>();
        for (ShopSection section : sections) {
            ShopPagination paging = new ShopPagination(section.entries().size(), perPage);
            for (int page = 0; page < paging.pageCount(); page++) {
                int first = page * perPage;
                int last = Math.min(first + perPage, section.entries().size());
                pages.add(new ShopPageView(section.id(), section.title(), page, paging.pageCount(),
                        first >= last ? List.of() : List.copyOf(section.entries().subList(first, last))));
            }
        }
        return List.copyOf(pages);
    }

    /** Every entry by id, in catalogue order. The purchase handler's lookup. */
    public Map<String, ShopEntry> byId() {
        Map<String, ShopEntry> index = new LinkedHashMap<>();
        sections.forEach(section -> section.entries().forEach(entry -> index.putIfAbsent(entry.id(), entry)));
        return Map.copyOf(index);
    }

    public int totalEntries() {
        return sections.stream().mapToInt(section -> section.entries().size()).sum();
    }
}
