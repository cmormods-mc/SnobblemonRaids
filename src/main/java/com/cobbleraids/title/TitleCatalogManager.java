package com.cobbleraids.title;

import com.cobbleraids.config.JsonFileStore;
import java.nio.file.Path;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the title catalogue from config/cobbleraids/titles.json. Same shape as
 * {@link com.cobbleraids.shop.ShopCatalogManager}, including the migration write-back that lets an
 * operator's older file gain whatever titles this build added.
 */
public final class TitleCatalogManager {
    private static final Path CATALOG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("titles.json");

    private record Loaded(TitleCatalog catalog, Map<String, TitleDefinition> index) {
        static Loaded of(TitleCatalog catalog) {
            return new Loaded(catalog, catalog.byId());
        }
    }

    private static volatile Loaded CURRENT = Loaded.of(TitleCatalog.defaults());

    private TitleCatalogManager() {}

    public static TitleCatalog get() { return CURRENT.catalog(); }

    /** Every title by id. Immutable, and shared rather than rebuilt. */
    public static Map<String, TitleDefinition> index() { return CURRENT.index(); }

    public static Path path() { return CATALOG_PATH; }

    public static synchronized TitleCatalog load() {
        TitleCatalog catalog = JsonFileStore.load(CATALOG_PATH, "title catalogue", TitleCatalog::defaults,
                TitleCatalog::fromJson, TitleCatalog::toJson);
        CURRENT = Loaded.of(catalog);
        return catalog;
    }

    public static synchronized TitleCatalog reload() { return load(); }
}
