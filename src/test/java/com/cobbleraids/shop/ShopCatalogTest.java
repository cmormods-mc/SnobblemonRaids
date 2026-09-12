package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shop catalogue: what it accepts, what it refuses, and what it does with a file an operator
 * got partly wrong.
 *
 * <p>The bias throughout is that one bad row costs one row. An operator who mistypes a single item
 * id and loses their entire shop has no way to tell that from a shop nobody stocked.
 */
class ShopCatalogTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("the shipped catalogue is internally consistent")
    void defaultsAreValid() {
        ShopCatalog catalog = ShopCatalog.defaults();

        assertEquals(ShopCatalog.CURRENT_VERSION, catalog.version());
        assertEquals(ShopCatalog.MAX_PER_PAGE, catalog.perPage());
        assertFalse(catalog.sections().isEmpty());
        assertEquals(catalog.totalEntries(), catalog.byId().size(), "entry ids are not unique");
        for (ShopEntry entry : catalog.byId().values()) {
            assertTrue(entry.cost() > 0, entry.id() + " is free");
            assertTrue((entry.item() == null) != (entry.pokemon() == null),
                    entry.id() + " does not carry exactly one payload");
        }
    }

    @Test
    @DisplayName("the shipped catalogue survives a round trip through its own file format")
    void defaultsRoundTrip() {
        // The migration write-back compares the canonical form against the file on disk, so a
        // field toJson forgets would be erased from an operator's catalogue on the next boot.
        ShopCatalog original = ShopCatalog.defaults();

        ShopCatalog reloaded = ShopCatalog.fromJson(original.toJson());

        assertEquals(original, reloaded);
        assertEquals(original.toJson(), reloaded.toJson());
    }

    @Test
    @DisplayName("a fixed Pokemon keeps every pinned field across a round trip")
    void pokemonRoundTrip() {
        ShopPokemonGift gift = new ShopPokemonGift("dratini", 15, true, "adamant", "shed-skin",
                "male", "normal", "dragon", "cobblemon:life_orb",
                Map.of("hp", 31, "speed", 30), Map.of("attack", 252));
        ShopCatalog catalog = new ShopCatalog(1, 72, List.of(
                new ShopSection("mons", "Pokemon", List.of(ShopEntry.ofPokemon("d", 100, gift, true)))));

        ShopCatalog reloaded = ShopCatalog.fromJson(catalog.toJson());
        ShopPokemonGift back = reloaded.byId().get("d").pokemon();

        assertEquals(gift, back);
        assertTrue(reloaded.byId().get("d").oncePerPlayer());
        assertEquals("Shiny Dratini", back.displayName());
    }

    @Test
    @DisplayName("a Pokemon entry may hold an item a raid boss is not allowed to")
    void shopPokemonIsNotBoundByBossHeldItemRules() {
        // RaidBossTraits refuses Focus Sash and Life Orb because a boss's simulated HP is pinned.
        // A bought Pokemon sits in a party, where none of that applies, and stripping its held item
        // would be silent theft of something the operator priced.
        ShopPokemonGift gift = new ShopPokemonGift("dratini", 50, false, null, null, null, null, null,
                "cobblemon:focus_sash", Map.of(), Map.of());

        ShopCatalog catalog = ShopCatalog.fromJson(new ShopCatalog(1, 72, List.of(
                new ShopSection("mons", "Pokemon", List.of(ShopEntry.ofPokemon("d", 100, gift, false)))))
                .toJson());

        assertEquals("cobblemon:focus_sash", catalog.byId().get("d").pokemon().heldItem());
    }

    @Test
    @DisplayName("one unusable entry costs that entry and nothing else")
    void badEntriesAreDroppedIndividually() {
        ShopCatalog catalog = ShopCatalog.fromJson(parse("""
                {
                  "version": 1,
                  "per_page": 72,
                  "sections": [{
                    "id": "misc", "title": "Misc",
                    "entries": [
                      {"id": "good", "cost": 10, "item": "cobblemon:poke_ball", "count": 4},
                      {"id": "no_namespace", "cost": 10, "item": "poke_ball"},
                      {"id": "no_cost", "item": "cobblemon:poke_ball"},
                      {"id": "both", "cost": 10, "item": "cobblemon:poke_ball", "species": "dratini"},
                      {"id": "neither", "cost": 10},
                      {"id": "BAD ID", "cost": 10, "item": "cobblemon:poke_ball"},
                      {"id": "also_good", "cost": 20, "species": "dratini", "level": 5}
                    ]
                  }]
                }
                """));

        assertEquals(Set.of("good", "also_good"), catalog.byId().keySet());
        assertEquals(4, catalog.byId().get("good").item().count());
        assertTrue(catalog.byId().get("also_good").isPokemon());
    }

    @Test
    @DisplayName("a duplicate entry id is dropped, catalogue-wide, not just within its section")
    void duplicateEntryIdsAreDropped() {
        // A purchase names an id. Two entries answering to one means the player gets whichever the
        // lookup reached first, which is not a thing an operator can debug from a price list.
        ShopCatalog catalog = ShopCatalog.fromJson(parse("""
                {
                  "sections": [
                    {"id": "a", "title": "A", "entries": [
                      {"id": "ball", "cost": 10, "item": "cobblemon:poke_ball"}]},
                    {"id": "b", "title": "B", "entries": [
                      {"id": "ball", "cost": 999, "item": "cobblemon:master_ball"},
                      {"id": "other", "cost": 5, "item": "cobblemon:great_ball"}]}
                  ]
                }
                """));

        assertEquals(2, catalog.totalEntries());
        assertEquals(10, catalog.byId().get("ball").cost());
        assertEquals("cobblemon:poke_ball", catalog.byId().get("ball").item().itemId());
    }

    @Test
    @DisplayName("a duplicate section is dropped whole")
    void duplicateSectionsAreDropped() {
        ShopCatalog catalog = ShopCatalog.fromJson(parse("""
                {
                  "sections": [
                    {"id": "a", "title": "First", "entries": []},
                    {"id": "a", "title": "Second", "entries": [
                      {"id": "x", "cost": 1, "item": "cobblemon:poke_ball"}]}
                  ]
                }
                """));

        assertEquals(1, catalog.sections().size());
        assertEquals("First", catalog.sections().get(0).title());
        assertEquals(0, catalog.totalEntries());
    }

    @Test
    @DisplayName("an out-of-range page size falls back rather than throwing the file away")
    void perPageIsClamped() {
        assertEquals(72, ShopCatalog.fromJson(parse("{\"per_page\": 500}")).perPage());
        assertEquals(72, ShopCatalog.fromJson(parse("{\"per_page\": 0}")).perPage());
        assertEquals(9, ShopCatalog.fromJson(parse("{\"per_page\": 9}")).perPage());
    }

    @Test
    @DisplayName("an empty file loads as an empty catalogue with one page to draw")
    void emptyFile() {
        ShopCatalog catalog = ShopCatalog.fromJson(parse("{}"));

        assertEquals(0, catalog.totalEntries());
        assertTrue(catalog.pages().isEmpty());
        assertTrue(catalog.byId().isEmpty());
    }

    @Test
    @DisplayName("each section starts its own page")
    void sectionsStartNewPages() {
        // So that adding one item to the first shelf does not shift every later page along and
        // change what a player's remembered page number lands on.
        ShopCatalog catalog = new ShopCatalog(1, 4, List.of(
                new ShopSection("a", "A", entries("a", 5)),
                new ShopSection("b", "B", entries("b", 2))));

        List<ShopPageView> pages = catalog.pages();

        assertEquals(3, pages.size());
        assertEquals(List.of("a", "a", "b"), pages.stream().map(ShopPageView::sectionId).collect(Collectors.toList()));
        assertEquals(4, pages.get(0).entries().size());
        assertEquals(1, pages.get(1).entries().size());
        assertEquals(2, pages.get(2).entries().size());
        assertEquals("A (1/2)", pages.get(0).heading());
        assertEquals("A (2/2)", pages.get(1).heading());
        assertEquals("B", pages.get(2).heading(), "a single-page section should not number itself");
    }

    @Test
    @DisplayName("an empty section still gets a page, so an unstocked shelf is visible")
    void emptySectionStillHasAPage() {
        ShopCatalog catalog = new ShopCatalog(1, 72, List.of(new ShopSection("a", "A", List.of())));

        assertEquals(1, catalog.pages().size());
        assertEquals(0, catalog.pages().get(0).entries().size());
        assertNull(catalog.pages().get(0).entryAt(0));
    }

    @Test
    @DisplayName("every entry on every page resolves back to the catalogue exactly once")
    void pagesCoverTheCatalogueExactly() {
        ShopCatalog catalog = ShopCatalog.defaults();

        List<String> paged = catalog.pages().stream()
                .flatMap(page -> page.entries().stream())
                .map(ShopEntry::id)
                .collect(Collectors.toList());

        assertEquals(catalog.totalEntries(), paged.size());
        assertEquals(catalog.byId().keySet(), Set.copyOf(paged));
    }

    @Test
    @DisplayName("a click past the end of a page buys nothing")
    void slotsPastTheEndAreEmpty() {
        ShopCatalog catalog = new ShopCatalog(1, 72, List.of(
                new ShopSection("a", "A", entries("a", 3))));
        ShopPageView page = catalog.pages().get(0);

        assertNotNull(page.entryAt(2));
        assertNull(page.entryAt(3));
        assertNull(page.entryAt(71));
        assertNull(page.entryAt(-1));
    }

    @Test
    @DisplayName("an entry that carries both payloads, or neither, cannot be constructed at all")
    void entryPayloadIsExclusive() {
        ShopPokemonGift gift = new ShopPokemonGift("dratini", 5, false, null, null, null, null, null,
                null, Map.of(), Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> new ShopEntry("x", 10, false, new ShopItemGift("cobblemon:poke_ball", 1), gift));
        assertThrows(IllegalArgumentException.class, () -> new ShopEntry("x", 10, false, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ShopEntry("Bad Id", 10, false, null, gift));
        assertThrows(IllegalArgumentException.class, () -> new ShopEntry("x", -1, false, null, gift));
    }

    @Test
    @DisplayName("absurd stacks and levels are refused")
    void payloadBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new ShopItemGift("cobblemon:poke_ball", 0));
        assertThrows(IllegalArgumentException.class, () -> new ShopItemGift("cobblemon:poke_ball", 65));
        assertThrows(IllegalArgumentException.class, () -> new ShopPokemonGift("dratini", 0, false,
                null, null, null, null, null, null, Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ShopPokemonGift("dratini", 101, false,
                null, null, null, null, null, null, Map.of(), Map.of()));
    }

    @Test
    @DisplayName("an out-of-range IV is dropped without taking the entry with it")
    void badStatsAreDroppedNotFatal() {
        ShopCatalog catalog = ShopCatalog.fromJson(parse("""
                {
                  "sections": [{"id": "a", "title": "A", "entries": [
                    {"id": "d", "cost": 10, "species": "dratini", "level": 5,
                     "traits": {"ivs": {"hp": 31, "speed": 99, "nonsense": 5}}}
                  ]}]
                }
                """));

        ShopPokemonGift gift = catalog.byId().get("d").pokemon();
        assertEquals(Map.of("hp", 31), gift.ivs());
    }

    private static List<ShopEntry> entries(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> ShopEntry.ofItem(prefix + "_" + index, 10, "cobblemon:poke_ball", 1))
                .collect(Collectors.toList());
    }
}
