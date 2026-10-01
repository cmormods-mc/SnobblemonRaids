package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
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
public record ShopCatalog(int version, int perPage, ShopLimits limits, List<ShopSection> sections,
                          ShopRotationConfig rotation) {

    /** Bumped when the schema changes in a way an older file cannot be read as. */
    public static final int CURRENT_VERSION = 3;

    /** The section the Cobblemon Cards packs sit in, and what a pack costs and how often it can be bought. */
    public static final String CARDS_SECTION = "cards";
    static final int CARD_PACK_COST = 75;
    static final int CARD_PACK_LIMIT = 2;
    private static final List<String> CARD_PACKS = List.of("booster_pack",
            "booster_pack_bug", "booster_pack_dark", "booster_pack_dragon", "booster_pack_electric",
            "booster_pack_fairy", "booster_pack_fighting", "booster_pack_fire", "booster_pack_flying",
            "booster_pack_ghost", "booster_pack_grass", "booster_pack_ground", "booster_pack_ice",
            "booster_pack_normal", "booster_pack_poison", "booster_pack_psychic", "booster_pack_rock",
            "booster_pack_steel", "booster_pack_water");

    /**
     * One pack of each of the 19 kinds, weekly-limited per pack. This is the one shelf that names an
     * optional mod: a server without Cobblemon Cards sees no such page (the shop hides an entry whose
     * item is not registered) and a stale click is refused before anything is charged.
     */
    static ShopSection cardsSection() {
        List<ShopEntry> entries = new ArrayList<>();
        for (String pack : CARD_PACKS) {
            entries.add(ShopEntry.ofItem(pack, CARD_PACK_COST, "cobblemon-cards:" + pack, 1,
                    CARD_PACK_LIMIT, ShopResetPeriod.WEEKLY));
        }
        return new ShopSection(CARDS_SECTION, "Card Packs", entries);
    }

    /** The sliced art's grid is eight by eight, so a page can never usefully hold more. */
    public static final int MAX_PER_PAGE = 64;

    /** A catalogue with the default rotation; what every hand-built catalogue in a test wants. */
    public ShopCatalog(int version, int perPage, ShopLimits limits, List<ShopSection> sections) {
        this(version, perPage, limits, sections, ShopRotationConfig.DEFAULTS);
    }

    public ShopCatalog {
        if (rotation == null) rotation = ShopRotationConfig.DEFAULTS;
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
                        // Priced so a level costs about 15 RP on average: a Rare Candy is exactly one
                        // level, and Large (10,000 exp) and XL (30,000) are about one and three
                        // average levels each. One candy per purchase, with the daily limit as the
                        // real throttle, so the price is per candy and cannot be bought in bundles.
                        ShopEntry.ofItem("rare_candy", 15, "cobblemon:rare_candy", 1),
                        ShopEntry.ofItem("exp_candy_l", 30, "cobblemon:exp_candy_l", 1),
                        ShopEntry.ofItem("exp_candy_xl", 75, "cobblemon:exp_candy_xl", 1, 3,
                                ShopLimits.DEFAULTS.reset()))),
                new ShopSection("held_items", "Held Items", List.of(
                        heldItem("leftovers", 150, "cobblemon:leftovers"),
                        heldItem("choice_band", 175, "cobblemon:choice_band"),
                        heldItem("choice_specs", 175, "cobblemon:choice_specs"),
                        heldItem("choice_scarf", 175, "cobblemon:choice_scarf"),
                        heldItem("life_orb", 200, "cobblemon:life_orb"),
                        heldItem("focus_sash", 200, "cobblemon:focus_sash"),
                        heldItem("assault_vest", 175, "cobblemon:assault_vest"),
                        heldItem("rocky_helmet", 150, "cobblemon:rocky_helmet"),
                        ShopEntry.ofItem("lucky_egg", 600, "cobblemon:lucky_egg", 1, 3, ShopResetPeriod.WEEKLY),
                        ShopEntry.ofItem("exp_share", 400, "cobblemon:exp_share", 1, 3, ShopResetPeriod.WEEKLY))),
                new ShopSection("training", "Training", List.of(
                        ShopEntry.ofItem("ability_capsule", 300, "cobblemon:ability_capsule", 1, 1, ShopResetPeriod.DAILY),
                        ShopEntry.ofItem("ability_patch", 600, "cobblemon:ability_patch", 1, 1, ShopResetPeriod.DAILY),
                        ShopEntry.ofItem("pp_up", 5, "cobblemon:pp_up", 1, ShopEntry.UNLIMITED, ShopResetPeriod.DAILY),
                        ShopEntry.ofItem("pp_max", 15, "cobblemon:pp_max", 1, ShopEntry.UNLIMITED, ShopResetPeriod.DAILY),
                        ShopEntry.ofItem("protein", 60, "cobblemon:protein", 2),
                        ShopEntry.ofItem("calcium", 60, "cobblemon:calcium", 2),
                        heldItem("destiny_knot", 50, "cobblemon:destiny_knot"),
                        heldItem("everstone", 35, "cobblemon:everstone"),
                        heldItem("link_cable", 75, "cobblemon:link_cable"),
                        heldItem("mirror_herb", 35, "cobblemon:mirror_herb"))),
                cardsSection());
        return new ShopCatalog(CURRENT_VERSION, MAX_PER_PAGE, ShopLimits.DEFAULTS, sections,
                ShopRotationConfig.DEFAULTS);
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
            boolean droppedSuperseded = false;
            for (ShopEntry original : section.entries()) {
                if (original.equals(SUPERSEDED_EXAMPLE_POKEMON)) {
                    droppedSuperseded = true;
                    continue;
                }
                ShopEntry entry = migrated(original);
                if (seenEntries.add(entry.id())) {
                    kept.add(entry);
                } else {
                    RaidLog.error("shop catalogue: dropping a second entry called " + entry.id()
                            + " in section " + section.id());
                }
            }
            // The old worked example was the only thing in its section, and the rotating page
            // replaces it. A section left empty by dropping it goes too, rather than showing a
            // blank page; one the operator left empty themselves is theirs and stays.
            if (droppedSuperseded && kept.isEmpty()) continue;
            sections.add(new ShopSection(section.id(), section.title(), kept));
        }
        // A catalogue written before the card packs existed gets their shelf once, as the last page so
        // no remembered page number moves. The version it is saved with is what stops it coming back
        // after an operator deletes it, and an entry id they already used for something else wins.
        if (version < 3 && sections.stream().noneMatch(section -> section.id().equals(CARDS_SECTION))
                && seenSections.add(CARDS_SECTION)) {
            List<ShopEntry> packs = new ArrayList<>();
            for (ShopEntry pack : cardsSection().entries()) {
                if (seenEntries.add(pack.id())) packs.add(pack);
            }
            if (!packs.isEmpty()) sections.add(new ShopSection(CARDS_SECTION, "Card Packs", packs));
        }
        return new ShopCatalog(Math.max(version, CURRENT_VERSION), perPage, limits, sections,
                ShopRotationConfig.fromJson(root));
    }

    /** A held item or one-off tool: one per purchase, one purchase a day. */
    private static ShopEntry heldItem(String id, int cost, String itemId) {
        return ShopEntry.ofItem(id, cost, itemId, 1, 1, ShopResetPeriod.DAILY);
    }

    private static ShopEntry was(String id, int cost, int count) {
        return ShopEntry.ofItem(id, cost, "cobblemon:" + id, count);
    }

    /**
     * The listings as they shipped before the exp-candy, exp-booster, ability, vitamin and held-item
     * repricing. An entry that is still exactly one of these was never edited, so it moves to the
     * new listing; any other is an operator's own price and stays as written.
     */
    private static final Map<String, ShopEntry> SUPERSEDED = Map.ofEntries(
            Map.entry("rare_candy", was("rare_candy", 75, 1)),
            Map.entry("exp_candy_l", was("exp_candy_l", 40, 4)),
            Map.entry("exp_candy_xl", was("exp_candy_xl", 90, 2)),
            Map.entry("lucky_egg", was("lucky_egg", 250, 1)),
            Map.entry("exp_share", was("exp_share", 200, 1)),
            Map.entry("ability_capsule", was("ability_capsule", 300, 1)),
            Map.entry("ability_patch", was("ability_patch", 600, 1)),
            Map.entry("protein", was("protein", 80, 4)),
            Map.entry("calcium", was("calcium", 80, 4)),
            Map.entry("leftovers", was("leftovers", 150, 1)),
            Map.entry("choice_band", was("choice_band", 175, 1)),
            Map.entry("choice_specs", was("choice_specs", 175, 1)),
            Map.entry("choice_scarf", was("choice_scarf", 175, 1)),
            Map.entry("life_orb", was("life_orb", 200, 1)),
            Map.entry("focus_sash", was("focus_sash", 200, 1)),
            Map.entry("assault_vest", was("assault_vest", 175, 1)),
            Map.entry("rocky_helmet", was("rocky_helmet", 150, 1)),
            Map.entry("pp_up", was("pp_up", 100, 1)),
            Map.entry("pp_max", was("pp_max", 350, 1)),
            Map.entry("destiny_knot", was("destiny_knot", 250, 1)),
            Map.entry("everstone", was("everstone", 60, 1)),
            Map.entry("link_cable", was("link_cable", 120, 1)),
            Map.entry("mirror_herb", was("mirror_herb", 200, 1)));

    /** The fixed level-15 Dratini that shipped as the shop's one Pokemon, before the rotating page. */
    private static final ShopEntry SUPERSEDED_EXAMPLE_POKEMON = ShopEntry.ofPokemon("starter_dratini", 2500,
            new ShopPokemonGift("dratini", 15, false, "adamant", null, null, null, null, null,
                    Map.of("hp", 31, "attack", 31, "speed", 31), Map.of()));

    private static ShopEntry migrated(ShopEntry entry) {
        if (!entry.equals(SUPERSEDED.get(entry.id()))) return entry;
        return defaultsEntry(entry.id());
    }

    private static ShopEntry defaultsEntry(String id) {
        return defaults().sections().stream().flatMap(section -> section.entries().stream())
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("per_page", perPage);
        root.add("limits", limits.toJson());
        JsonArray array = new JsonArray();
        sections.forEach(section -> array.add(section.toJson()));
        root.add("sections", array);
        root.add("rotation", rotation.toJson());
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
        // Wrapped rather than Map.copyOf, whose iteration order is unspecified: this map's key set
        // is what tab-completion offers, and catalogue order is the order an operator wrote and the
        // only one a player can predict.
        return Collections.unmodifiableMap(index);
    }

    public int totalEntries() {
        return sections.stream().mapToInt(section -> section.entries().size()).sum();
    }
}
