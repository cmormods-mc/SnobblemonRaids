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
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the shop catalogue from config/cobbleraids/shop.json.
 *
 * <p>Same shape as the config and reward-policy managers, including the migration write-back: an
 * operator's older file keeps their prices and gains whatever this build added, which is the only
 * way they would ever find out a new field exists.
 *
 * <p>A malformed file keeps the last known good catalogue rather than replacing it with an empty
 * one. A shop that quietly sells nothing looks exactly like a shop nobody has stocked.
 */
public final class ShopCatalogManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CATALOG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("shop.json");
    private static volatile ShopCatalog CURRENT = ShopCatalog.defaults();

    private ShopCatalogManager() {}

    public static ShopCatalog get() { return CURRENT; }
    public static Path path() { return CATALOG_PATH; }

    public static synchronized ShopCatalog load() {
        try {
            Files.createDirectories(CATALOG_PATH.getParent());
            if (!Files.exists(CATALOG_PATH)) {
                CURRENT = ShopCatalog.defaults();
                write(CURRENT);
                RaidLog.info("Created default shop catalogue: " + CATALOG_PATH);
                return CURRENT;
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(CATALOG_PATH, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            CURRENT = ShopCatalog.fromJson(root);

            JsonObject canonical = CURRENT.toJson();
            if (!canonical.equals(root)) {
                write(CURRENT);
                RaidLog.info("Updated " + CATALOG_PATH + " with settings new to this version.");
            }
            return CURRENT;
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
