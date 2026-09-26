package com.cobbleraids.shop;

import com.cobbleraids.config.JsonFileStore;
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
        ShopCatalog catalog = JsonFileStore.load(CATALOG_PATH, "shop catalogue", ShopCatalog::defaults,
                ShopCatalog::fromJson, ShopCatalog::toJson);
        CURRENT = Loaded.of(catalog);
        return catalog;
    }

    public static synchronized ShopCatalog reload() { return load(); }
}
