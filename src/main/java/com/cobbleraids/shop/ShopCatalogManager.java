package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the shop catalogue from config/cobbleraids/shop.json, and holds what is derived from it.
 *
 * <p>Same shape as the config and reward-policy managers, including the migration write-back: an
 * operator's older file keeps their prices and gains whatever this build added, which is the only
 * way they would ever find out a new field exists.
 *
 * <p>A malformed file keeps the last known good catalogue rather than replacing it with an empty
 * one. A shop that quietly sells nothing looks exactly like a shop nobody has stocked.
 *
 * <p>{@link #index()} and {@link #pages()} are computed once per load rather than per call. They
 * used to be recomputed at every call site -- which meant rebuilding a 165-entry map on every
 * tab-completion keystroke, and rebuilding both the map and the whole page list twice for every
 * click in the shop. The catalogue only changes when this class reloads it, so that work belongs
 * here. The three are swapped together behind one reference so a reload cannot be observed
 * half-applied.
 */
public final class ShopCatalogManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CATALOG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("shop.json");

    /** The catalogue and its derived views, always consistent with each other. */
    private record Loaded(ShopCatalog catalog, Map<String, ShopEntry> index, List<ShopPageView> pages) {
        static Loaded of(ShopCatalog catalog) {
            return new Loaded(catalog, catalog.byId(), catalog.pages());
        }
    }

    private static volatile Loaded CURRENT = Loaded.of(ShopCatalog.defaults());

    private ShopCatalogManager() {}

    public static ShopCatalog get() { return CURRENT.catalog(); }

    /** Every entry by id. Immutable, and shared rather than rebuilt. */
    public static Map<String, ShopEntry> index() { return CURRENT.index(); }

    /** The catalogue flattened into pages. Immutable, and shared rather than rebuilt. */
    public static List<ShopPageView> pages() { return CURRENT.pages(); }

    public static Path path() { return CATALOG_PATH; }

    public static synchronized ShopCatalog load() {
        try {
            Files.createDirectories(CATALOG_PATH.getParent());
            if (!Files.exists(CATALOG_PATH)) {
                CURRENT = Loaded.of(ShopCatalog.defaults());
                write(CURRENT.catalog());
                RaidLog.info("Created default shop catalogue: " + CATALOG_PATH);
                return CURRENT.catalog();
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(CATALOG_PATH, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            CURRENT = Loaded.of(ShopCatalog.fromJson(root));

            JsonObject canonical = CURRENT.catalog().toJson();
            if (!canonical.equals(root)) {
                write(CURRENT.catalog());
                RaidLog.info("Updated " + CATALOG_PATH + " with settings new to this version.");
            }
            return CURRENT.catalog();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load shop catalogue " + CATALOG_PATH, ex);
        }
    }

    public static synchronized ShopCatalog reload() { return load(); }

    private static void write(ShopCatalog catalog) throws Exception {
        try (Writer writer = Files.newBufferedWriter(CATALOG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(catalog.toJson(), writer);
        }
    }
}
