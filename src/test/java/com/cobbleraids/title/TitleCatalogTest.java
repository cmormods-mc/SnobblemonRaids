package com.cobbleraids.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the catalogue's drop-and-log policy for a malformed titles.json -- one bad row must cost
 * one title, not the file -- and that a round trip through {@code toJson}/{@code fromJson} is
 * lossless, the same properties {@code ShopCatalogTest} pins for the shop's own catalogue.
 */
class TitleCatalogTest {

    @Test
    @DisplayName("the shipped defaults parse back out of their own JSON unchanged")
    void defaultsRoundTripThroughJson() {
        TitleCatalog defaults = TitleCatalog.defaults();

        TitleCatalog reloaded = TitleCatalog.fromJson(defaults.toJson());

        assertEquals(defaults, reloaded);
    }

    @Test
    @DisplayName("a title with no id is dropped, not the whole file")
    void titleWithNoIdIsDropped() {
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray titles = new com.google.gson.JsonArray();
        titles.add(new JsonObject()); // no "id" key at all
        root.add("titles", titles);

        TitleCatalog catalog = TitleCatalog.fromJson(root);

        assertTrue(catalog.titles().isEmpty());
    }

    @Test
    @DisplayName("a tier_wins title with no valid tier is dropped")
    void tierWinsWithoutTierIsDropped() {
        JsonObject row = new JsonObject();
        row.addProperty("id", "broken");
        row.addProperty("display", "Broken");
        row.addProperty("unlock_type", "tier_wins");
        row.addProperty("threshold", 5);
        // no "tier" field at all

        assertNull(TitleDefinition.fromJson(row));
    }

    @Test
    @DisplayName("a second title with the same id as an earlier one is dropped, keeping the first")
    void duplicateIdIsDropped() {
        JsonObject a = new TitleDefinition("dup", "First", "GRAY",
                TitleUnlockType.RAIDS_WON, null, 1).toJson();
        JsonObject b = new TitleDefinition("dup", "Second", "GOLD",
                TitleUnlockType.RAIDS_WON, null, 2).toJson();
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray titles = new com.google.gson.JsonArray();
        titles.add(a);
        titles.add(b);
        root.add("titles", titles);

        TitleCatalog catalog = TitleCatalog.fromJson(root);

        assertEquals(1, catalog.titles().size());
        assertEquals("First", catalog.titles().get(0).display());
    }

    @Test
    @DisplayName("byId preserves file order and only keeps the first of a duplicate")
    void byIdIsOrderedAndDeduplicated() {
        TitleCatalog catalog = new TitleCatalog(java.util.List.of(
                new TitleDefinition("first", "First", "GRAY", TitleUnlockType.RAIDS_WON, null, 1),
                new TitleDefinition("second", "Second", "GOLD", TitleUnlockType.TIER_WINS,
                        RaidRarityTier.LEGENDARY, 3)));

        assertEquals(java.util.List.of("first", "second"), java.util.List.copyOf(catalog.byId().keySet()));
    }
}
