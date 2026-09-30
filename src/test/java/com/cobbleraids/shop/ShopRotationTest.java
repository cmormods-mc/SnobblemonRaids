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
        assertEquals(1250, ShopRotation.price(45, 600, CONFIG.maxLevel(), CONFIG));
        assertEquals(1250, ShopRotation.price(3, 720, CONFIG.maxLevel(), CONFIG), "rarer than the ceiling is clamped");
        for (int catchRate = 3; catchRate <= 255; catchRate += 4) {
            for (int bst = 180; bst <= 720; bst += 30) {
                for (int level = CONFIG.minLevel(); level <= CONFIG.maxLevel(); level += 5) {
                    int price = ShopRotation.price(catchRate, bst, level, CONFIG);
                    assertTrue(price <= 1250 && price >= CONFIG.minPrice(), price + " is outside the band");
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
    @DisplayName("a common weak species at a low level is cheap, and a pseudo-legendary at a high level is dear")
    void anchorPrices() {
        int rattata = ShopRotation.price(255, 253, 10, CONFIG);
        int dratiniLevel15 = ShopRotation.price(45, 300, 15, CONFIG);
        int dragonite = ShopRotation.price(45, 600, 50, CONFIG);

        assertTrue(rattata < 50, "a level-10 Rattata is " + rattata);
        assertTrue(dratiniLevel15 > rattata && dratiniLevel15 < 400, "a level-15 Dratini is " + dratiniLevel15);
        assertEquals(1250, dragonite);
    }

    @Test
    @DisplayName("the same window always produces the same ten listings, traits included")
    void deterministicPerWindow() {
        assertEquals(ShopRotation.roll(123_456L, pool(), CONFIG), ShopRotation.roll(123_456L, pool(), CONFIG));
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
        for (long window = 100_000L; window < 100_200L; window++) {
            List<ShopRotation.Listing> listings = ShopRotation.roll(window, pool(), CONFIG);

            assertEquals(10, listings.size());
            Set<String> seen = new HashSet<>();
            for (ShopRotation.Listing listing : listings) {
                assertTrue(seen.add(listing.gift().species()), "duplicate species in window " + window);
                assertTrue(listing.gift().level() >= 5 && listing.gift().level() <= 50);
                assertTrue(listing.cost() <= 1250 && listing.cost() >= 25);
                assertEquals(false, listing.gift().shiny(), "a shop Pokemon is never shiny");
                assertEquals(6, listing.gift().ivs().size());
                listing.gift().ivs().values().forEach(iv -> assertTrue(iv >= 0 && iv <= 31));
            }
        }
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
        ShopRotationConfig off = new ShopRotationConfig(false, 10, 5, 50, 25, 1250, List.of());

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
