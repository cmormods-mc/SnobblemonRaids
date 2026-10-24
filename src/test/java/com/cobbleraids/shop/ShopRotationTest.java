package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rotating Pokemon page. The properties that matter are the ones a player would notice if they
 * broke: the same page for everyone, the same page after a restart, a hard price ceiling, and a
 * price that follows both how rare a species is and how high its level is.
 */
class ShopRotationTest {

    private static final ShopRotationConfig CONFIG = ShopRotationConfig.DEFAULTS;

    private static ShopRotation.Candidate species(String id, int catchRate, int bst) {
        return new ShopRotation.Candidate(id, catchRate, bst, List.of("ability_a", "ability_b"), 0.5f);
    }

    /** A pool with enough spread and enough members that rolls differ between windows. */
    private static List<ShopRotation.Candidate> pool() {
        List<ShopRotation.Candidate> pool = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            pool.add(species("species_" + index, 45 + (index * 7) % 211, 250 + (index * 13) % 351));
        }
        return pool;
    }

    @Test
    @DisplayName("the hardest species at the top level costs exactly the ceiling, and nothing costs more")
    void ceilingIsTheMaximum() {
        assertEquals(500, ShopRotation.price(45, 600, CONFIG.maxLevel(), CONFIG));
        assertEquals(500, ShopRotation.price(3, 720, CONFIG.maxLevel(), CONFIG), "rarer than the ceiling is clamped");
        for (int catchRate = 3; catchRate <= 255; catchRate += 4) {
            for (int bst = 180; bst <= 720; bst += 30) {
                for (int level = CONFIG.minLevel(); level <= CONFIG.maxLevel(); level += 5) {
                    int price = ShopRotation.price(catchRate, bst, level, CONFIG);
                    assertTrue(price <= 500 && price >= CONFIG.minPrice(), price + " is outside the band");
                }
            }
        }
    }

    @Test
    @DisplayName("price rises with level, with rarity and with power, and never falls as any of them grows")
    void priceIsMonotonic() {
        for (int level = CONFIG.minLevel() + 1; level <= CONFIG.maxLevel(); level++) {
            assertTrue(ShopRotation.price(100, 450, level, CONFIG) >= ShopRotation.price(100, 450, level - 1, CONFIG));
        }
        for (int catchRate = 254; catchRate >= 45; catchRate--) {
            assertTrue(ShopRotation.price(catchRate, 450, 30, CONFIG) >= ShopRotation.price(catchRate + 1, 450, 30, CONFIG));
        }
        for (int bst = 251; bst <= 600; bst++) {
            assertTrue(ShopRotation.price(100, bst, 30, CONFIG) >= ShopRotation.price(100, bst - 1, 30, CONFIG));
        }
    }

    @Test
    @DisplayName("a common weak species is at the floor, and a pseudo-legendary at the top level is the ceiling")
    void anchorPrices() {
        int rattata = ShopRotation.price(255, 253, CONFIG.minLevel(), CONFIG);
        int dratiniAtTheBottom = ShopRotation.price(45, 300, CONFIG.minLevel(), CONFIG);
        int dragonite = ShopRotation.price(45, 600, 50, CONFIG);

        assertEquals(100, rattata, "a level-25 Rattata is " + rattata);
        assertTrue(dratiniAtTheBottom > rattata && dratiniAtTheBottom < 300, "a level-25 Dratini is " + dratiniAtTheBottom);
        assertEquals(500, dragonite);
    }

    @Test
    @DisplayName("the page offers levels 25 to 50 and nothing costs less than 100 or more than 500")
    void bandsAreTheRetunedOnes() {
        assertEquals(25, CONFIG.minLevel());
        assertEquals(50, CONFIG.maxLevel());
        assertEquals(100, CONFIG.minPrice());
        assertEquals(500, CONFIG.maxPrice());
    }

    @Test
    @DisplayName("a starter line costs 250 / 375 / 500 by stage, whatever the level, and nothing else is flat")
    void starterLinesAreFlatByStage() {
        List<ShopRotation.Candidate> starters = List.of(
                species("charmander", 45, 309), species("charmeleon", 45, 405), species("charizard", 45, 534),
                species("torchic", 45, 310), species("blaziken", 45, 530), species("rowlet", 45, 320),
                species("somemod:charmander", 45, 309));

        for (long window = 1; window <= 60; window++) {
            for (ShopRotation.Listing listing : ShopRotation.roll(window, starters, withRarity(ShopRotationConfig.Rarity.NONE))) {
                String id = listing.gift().species();
                switch (id) {
                    case "charmander", "torchic", "rowlet" -> assertEquals(250, listing.cost(), id);
                    case "charmeleon" -> assertEquals(375, listing.cost(), id);
                    case "charizard", "blaziken" -> assertEquals(500, listing.cost(), id);
                    default -> assertTrue(listing.cost() >= 100 && listing.cost() <= 500, id + " " + listing.cost());
                }
            }
        }
    }

    @Test
    @DisplayName("lowering max_price lowers the starter prices with it, so nothing on the page can exceed it")
    void starterPricesStayUnderTheCeiling() {
        ShopRotationConfig cheap = new ShopRotationConfig(true, 10, 25, 50, 50, 300, List.of(), 250, 375, 500);

        assertEquals(250, cheap.starterPriceFor("charmander").getAsInt());
        assertEquals(300, cheap.starterPriceFor("charmeleon").getAsInt());
        assertEquals(300, cheap.starterPriceFor("charizard").getAsInt());
        assertTrue(cheap.starterPriceFor("pikachu").isEmpty());
    }

    @Test
    @DisplayName("an untouched pre-retune rotation block moves to the new bands; operator values stay")
    void rotationMigratesOnlyWhenUntouched() {
        var old = new com.google.gson.JsonObject();
        var block = new com.google.gson.JsonObject();
        block.addProperty("min_level", 5);
        block.addProperty("max_level", 50);
        block.addProperty("min_price", 25);
        block.addProperty("max_price", 1250);
        old.add("rotation", block);
        ShopRotationConfig migrated = ShopRotationConfig.fromJson(old);

        assertEquals(25, migrated.minLevel());
        assertEquals(100, migrated.minPrice());
        assertEquals(500, migrated.maxPrice());
        assertEquals(250, migrated.starterBasePrice(), "a key new to this version gets its default");

        var custom = new com.google.gson.JsonObject();
        var mine = new com.google.gson.JsonObject();
        mine.addProperty("min_level", 10);
        mine.addProperty("max_price", 900);
        custom.add("rotation", mine);
        ShopRotationConfig kept = ShopRotationConfig.fromJson(custom);
        assertEquals(10, kept.minLevel());
        assertEquals(900, kept.maxPrice());

        var narrow = new com.google.gson.JsonObject();
        var small = new com.google.gson.JsonObject();
        small.addProperty("min_level", 5);
        small.addProperty("max_level", 20);
        narrow.add("rotation", small);
        assertEquals(5, ShopRotationConfig.fromJson(narrow).minLevel(),
                "migrating min_level past a custom max_level would discard the whole block");
    }

    @Test
    @DisplayName("the same window always produces the same ten listings, traits included")
    void deterministicPerWindow() {
        assertEquals(ShopRotation.roll(123_456L, pool(), CONFIG), ShopRotation.roll(123_456L, pool(), CONFIG));
    }

    @Test
    @DisplayName("each player gets their own page for the same window, and the same one every time")
    void pagesArePerPlayer() {
        java.util.UUID alice = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
        java.util.UUID bob = java.util.UUID.fromString("66666666-7777-8888-9999-000000000000");
        long window = 123_456L;

        List<ShopRotation.Listing> a = ShopRotation.roll(window, ShopRotation.saltOf(alice), pool(), CONFIG);
        List<ShopRotation.Listing> b = ShopRotation.roll(window, ShopRotation.saltOf(bob), pool(), CONFIG);

        assertEquals(a, ShopRotation.roll(window, ShopRotation.saltOf(alice), pool(), CONFIG), "stable per player");
        assertNotEquals(a, b, "two players must not see the same page");
        assertNotEquals(a, ShopRotation.roll(window + 1, ShopRotation.saltOf(alice), pool(), CONFIG),
                "and a player's page changes with the window");
        // Not merely reordered: across many windows two players should overlap only by chance.
        int identicalPages = 0;
        for (long w = 1; w <= 500; w++) {
            if (ShopRotation.roll(w, ShopRotation.saltOf(alice), pool(), CONFIG)
                    .equals(ShopRotation.roll(w, ShopRotation.saltOf(bob), pool(), CONFIG))) identicalPages++;
        }
        assertEquals(0, identicalPages);
    }

    @Test
    @DisplayName("no player is the unsalted reference roll, so the pure function is unchanged")
    void noPlayerIsTheReferenceRoll() {
        assertEquals(0L, ShopRotation.saltOf(null));
        assertEquals(ShopRotation.roll(123_456L, pool(), CONFIG),
                ShopRotation.roll(123_456L, ShopRotation.saltOf(null), pool(), CONFIG));
    }

    @Test
    @DisplayName("the answer does not depend on the order the registry enumerated the species in")
    void independentOfPoolOrder() {
        List<ShopRotation.Candidate> reversed = new ArrayList<>(pool());
        java.util.Collections.reverse(reversed);

        assertEquals(ShopRotation.roll(123_456L, pool(), CONFIG), ShopRotation.roll(123_456L, reversed, CONFIG));
    }

    @Test
    @DisplayName("consecutive windows offer different stock")
    void windowsDiffer() {
        assertNotEquals(ShopRotation.roll(123_456L, pool(), CONFIG), ShopRotation.roll(123_457L, pool(), CONFIG));
    }

    @Test
    @DisplayName("ten distinct species, levels and prices inside the configured bounds")
    void listingsAreWellFormed() {
        // Rarity is off here: a shiny or a legendary is allowed past the ceiling, and is covered below.
        ShopRotationConfig plain = withRarity(ShopRotationConfig.Rarity.NONE);
        for (long window = 100_000L; window < 100_200L; window++) {
            List<ShopRotation.Listing> listings = ShopRotation.roll(window, pool(), plain);

            assertEquals(10, listings.size());
            Set<String> seen = new HashSet<>();
            for (ShopRotation.Listing listing : listings) {
                assertTrue(seen.add(listing.gift().species()), "duplicate species in window " + window);
                assertTrue(listing.gift().level() >= 25 && listing.gift().level() <= 50);
                assertTrue(listing.cost() <= 500 && listing.cost() >= 100);
                assertEquals(false, listing.gift().shiny(), "no shinies when the shiny chance is zero");
                assertEquals(6, listing.gift().ivs().size());
                listing.gift().ivs().values().forEach(iv -> assertTrue(iv >= 0 && iv <= 31));
            }
        }
    }

    private static ShopRotationConfig withRarity(ShopRotationConfig.Rarity rarity) {
        ShopRotationConfig d = ShopRotationConfig.DEFAULTS;
        return new ShopRotationConfig(d.enabled(), d.listings(), d.minLevel(), d.maxLevel(), d.minPrice(),
                d.maxPrice(), d.excludedLabels(), d.starterBasePrice(), d.starterMiddlePrice(),
                d.starterFinalPrice(), rarity);
    }

    private static List<ShopRotation.Candidate> poolWithLegendaries() {
        List<ShopRotation.Candidate> pool = new ArrayList<>(pool());
        for (int index = 0; index < 10; index++) {
            pool.add(new ShopRotation.Candidate("legend_" + index, 3, 680, List.of("a"), -1f, true));
        }
        return pool;
    }

    @Test
    @DisplayName("legendaries and shinies are rare but do turn up, at roughly the configured rate")
    void rareAndShinyAreRareButPossible() {
        int windows = 5_000, legendaries = 0, shinies = 0, slots = 0;
        for (long window = 200_000L; window < 200_000L + windows; window++) {
            for (ShopRotation.Listing listing : ShopRotation.roll(window, poolWithLegendaries(), CONFIG)) {
                slots++;
                if (listing.gift().species().startsWith("legend_")) legendaries++;
                if (listing.gift().shiny()) shinies++;
            }
        }
        // 2% of 50,000 slots is 1,000; a generous band, but wide enough to fail if either were 0 or ~20%.
        assertTrue(legendaries > 700 && legendaries < 1300, "legendaries: " + legendaries + " of " + slots);
        assertTrue(shinies > 700 && shinies < 1300, "shinies: " + shinies + " of " + slots);
    }

    @Test
    @DisplayName("a legendary never comes from the ordinary draw: with a zero rare chance there are none")
    void noRareChanceMeansNoLegendaries() {
        ShopRotationConfig none = withRarity(new ShopRotationConfig.Rarity(List.of("legendary"), 0.0, 0.0, 4.0, 3.0));
        for (long window = 1L; window <= 500L; window++) {
            for (ShopRotation.Listing listing : ShopRotation.roll(window, poolWithLegendaries(), none)) {
                assertTrue(!listing.gift().species().startsWith("legend_"), "window " + window);
            }
        }
    }

    @Test
    @DisplayName("rare and shiny listings cost their premium; ordinary ones stay under the ceiling")
    void premiumsApplyOnlyToTheRare() {
        ShopRotationConfig.Rarity rarity = ShopRotationConfig.Rarity.DEFAULTS;

        assertEquals(500, ShopRotation.premium(500, false, false, rarity));
        assertEquals(2000, ShopRotation.premium(500, true, false, rarity));
        assertEquals(1500, ShopRotation.premium(500, false, true, rarity));
        assertEquals(6000, ShopRotation.premium(500, true, true, rarity));
        for (long window = 1L; window <= 2_000L; window++) {
            for (ShopRotation.Listing listing : ShopRotation.roll(window, poolWithLegendaries(), CONFIG)) {
                boolean legendary = listing.gift().species().startsWith("legend_");
                if (!legendary && !listing.gift().shiny()) {
                    assertTrue(listing.cost() <= 500, "an ordinary listing broke the ceiling: " + listing);
                }
                if (legendary) assertTrue(listing.cost() >= 4 * 100, "a legendary priced like ordinary stock: " + listing);
            }
        }
    }

    @Test
    @DisplayName("an installed server with no legendaries still fills the page")
    void rareSlotsFallBackToOrdinaryStock() {
        ShopRotationConfig always = withRarity(new ShopRotationConfig.Rarity(List.of("legendary"), 1.0, 0.0, 4.0, 3.0));

        assertEquals(10, ShopRotation.roll(7L, pool(), always).size());
        // And the reverse: only rare species available still yields a full page.
        List<ShopRotation.Candidate> onlyRare = poolWithLegendaries().stream().filter(ShopRotation.Candidate::rare).toList();
        assertEquals(10, ShopRotation.roll(7L, onlyRare, withRarity(ShopRotationConfig.Rarity.NONE)).size());
    }

    @Test
    @DisplayName("a file that still lists the old banned labels gets the rare tier; an edited list is kept")
    void oldExclusionListMigrates() {
        com.google.gson.JsonObject old = com.google.gson.JsonParser.parseString("""
                {"rotation": {"excluded_labels": ["legendary","mythical","ultra_beast","paradox","restricted"]}}
                """).getAsJsonObject();
        assertEquals(List.of(), ShopRotationConfig.fromJson(old).excludedLabels());
        assertTrue(ShopRotationConfig.fromJson(old).rarity().rareChance() > 0.0);

        com.google.gson.JsonObject mine = com.google.gson.JsonParser.parseString("""
                {"rotation": {"excluded_labels": ["legendary"]}}
                """).getAsJsonObject();
        assertEquals(List.of("legendary"), ShopRotationConfig.fromJson(mine).excludedLabels());
    }

    @Test
    @DisplayName("a pool smaller than the page yields fewer listings rather than repeats")
    void smallPool() {
        List<ShopRotation.Listing> listings = ShopRotation.roll(1L, pool().subList(0, 4), CONFIG);

        assertEquals(4, listings.size());
        assertEquals(4, listings.stream().map(l -> l.gift().species()).distinct().count());
        assertTrue(ShopRotation.roll(1L, List.of(), CONFIG).isEmpty());
    }

    @Test
    @DisplayName("a genderless species is pinned genderless, and a species with no ordinary ability pins none")
    void traitsRespectTheSpecies() {
        List<ShopRotation.Candidate> only = List.of(new ShopRotation.Candidate("magnemite", 190, 325, List.of(), -1f));

        ShopRotation.Listing listing = ShopRotation.roll(9L, only, CONFIG).get(0);

        assertEquals("genderless", listing.gift().gender());
        assertNull(listing.gift().ability());
    }

    @Test
    @DisplayName("switching the rotation off leaves the page empty")
    void disabled() {
        ShopRotationConfig off = new ShopRotationConfig(false, 10, 5, 50, 25, 1250, List.of(), 250, 375, 500);

        assertTrue(ShopRotation.roll(1L, pool(), off).isEmpty());
    }

    @Test
    @DisplayName("a click names its window, and only a well-formed id parses")
    void wireIds() {
        long[] parsed = ShopRotation.parseWireId(ShopRotation.wireId(123_456L, 7));

        assertEquals(123_456L, parsed[0]);
        assertEquals(7L, parsed[1]);
        assertNull(ShopRotation.parseWireId("rotating.abc.1"));
        assertNull(ShopRotation.parseWireId("rotating.1"));
        assertNull(ShopRotation.parseWireId("rotating:123:1"));
        assertNull(ShopRotation.parseWireId("poke_ball"));
        assertNull(ShopRotation.parseWireId(null));
    }

    @Test
    @DisplayName("the window is four hours of UTC")
    void windowIsFourHours() {
        long a = ShopResetPeriod.ROTATION.windowOf(java.time.Instant.parse("2026-09-12T08:00:00Z"));
        long b = ShopResetPeriod.ROTATION.windowOf(java.time.Instant.parse("2026-09-12T11:59:59Z"));
        long c = ShopResetPeriod.ROTATION.windowOf(java.time.Instant.parse("2026-09-12T12:00:00Z"));

        assertEquals(a, b);
        assertEquals(a + 1, c);
    }

    @Test
    @DisplayName("the config round-trips, and a bad block falls back to the defaults instead of throwing")
    void configParsing() {
        var good = new com.google.gson.JsonObject();
        good.add("rotation", ShopRotationConfig.DEFAULTS.toJson());
        assertEquals(ShopRotationConfig.DEFAULTS, ShopRotationConfig.fromJson(good));
        var bad = new com.google.gson.JsonObject();
        var block = new com.google.gson.JsonObject();
        block.addProperty("min_level", 90);
        block.addProperty("max_level", 10);
        bad.add("rotation", block);

        assertEquals(ShopRotationConfig.DEFAULTS, ShopRotationConfig.fromJson(bad));
    }
}
