package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every item in the shipped catalogue has to be an item that actually exists on this pack.
 *
 * <p>Checked against the same manifest the reward economy is validated from, because the failure it
 * prevents is the one that cost this project a whole reward tier: an id that does not resolve is
 * not a missing row. Here it is worse than a missing row -- a player pays Raid Points and the grant
 * finds nothing to give them.
 *
 * <p>The second half of the check is the one that is easy to forget. Almost everything in this pack
 * comes from an optional mod, and a shipped default that names one is a shop that is broken on any
 * server without it. Defaults may only use namespaces that are always present.
 */
class ShopDefaultCatalogResolvesTest {

    /** Namespaces every server running this mod is guaranteed to have. */
    private static final Set<String> ALWAYS_PRESENT = Set.of("minecraft", "cobblemon");

    private static final Path MANIFEST = Path.of("validation", "economy", "manifest.json");

    @Test
    @DisplayName("every item in the shipped catalogue resolves against the pack manifest")
    void defaultItemsResolve() throws Exception {
        assertTrue(Files.exists(MANIFEST), "pack manifest is missing: " + MANIFEST.toAbsolutePath());
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(MANIFEST, StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject items = manifest.getAsJsonObject("items");

        List<String> unresolved = new ArrayList<>();
        for (ShopEntry entry : ShopCatalog.defaults().byId().values()) {
            if (entry.item() == null) continue;
            String id = entry.item().itemId();
            int colon = id.indexOf(':');
            String namespace = id.substring(0, colon);
            String path = id.substring(colon + 1);

            if (namespace.equals("minecraft")) continue;
            if (!items.has(namespace)) {
                unresolved.add(entry.id() + " -> " + id + " (namespace not in the pack)");
                continue;
            }
            Set<String> registry = new HashSet<>();
            items.getAsJsonArray(namespace).forEach(element -> registry.add(element.getAsString()));
            if (!registry.contains(path)) {
                unresolved.add(entry.id() + " -> " + id + " (not in " + namespace + ")");
            }
        }
        assertTrue(unresolved.isEmpty(), "shipped catalogue names items that do not exist: " + unresolved);
    }

    @Test
    @DisplayName("the shipped catalogue never depends on an optional mod")
    void defaultsUseOnlyGuaranteedNamespaces() {
        List<String> optional = new ArrayList<>();
        for (ShopEntry entry : ShopCatalog.defaults().byId().values()) {
            if (entry.item() == null) continue;
            String namespace = entry.item().itemId().split(":", 2)[0];
            if (!ALWAYS_PRESENT.contains(namespace)) {
                optional.add(entry.id() + " -> " + entry.item().itemId());
            }
        }
        assertTrue(optional.isEmpty(), "shipped catalogue depends on optional mods: " + optional);
    }

    @Test
    @DisplayName("a held item pinned on a shipped Pokemon resolves too")
    void defaultPokemonHeldItemsResolve() {
        // Priced Pokemon are the entries most likely to pin a held item, and a held item that does
        // not exist is the same silent nothing as a missing sale item.
        List<String> problems = new ArrayList<>();
        for (ShopEntry entry : ShopCatalog.defaults().byId().values()) {
            if (entry.pokemon() == null) continue;
            String held = entry.pokemon().heldItem();
            if (held == null) continue;
            if (!held.contains(":")) {
                problems.add(entry.id() + " held_item has no namespace: " + held);
                continue;
            }
            if (!ALWAYS_PRESENT.contains(held.split(":", 2)[0])) {
                problems.add(entry.id() + " held_item comes from an optional mod: " + held);
            }
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("the shipped catalogue actually sells something")
    void defaultsAreNotEmpty() {
        // A guard against a refactor that quietly empties defaults(): every other test here passes
        // trivially on an empty catalogue.
        ShopCatalog catalog = ShopCatalog.defaults();

        assertFalse(catalog.sections().isEmpty());
        assertTrue(catalog.totalEntries() >= 10, "only " + catalog.totalEntries() + " entries");
        assertTrue(catalog.byId().values().stream().anyMatch(ShopEntry::isPokemon),
                "no worked example of a Pokemon entry for operators to copy");
    }
}
