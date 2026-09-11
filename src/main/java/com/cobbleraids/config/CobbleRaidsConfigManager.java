package com.cobbleraids.config;

import com.cobbleraids.RaidLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the operator-facing config from config/cobbleraids/server.json. */
public final class CobbleRaidsConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("server.json");
    private static volatile CobbleRaidsConfig CURRENT = CobbleRaidsConfig.defaults();

    private CobbleRaidsConfigManager() {}

    public static CobbleRaidsConfig get() { return CURRENT; }
    public static Path path() { return CONFIG_PATH; }

    public static synchronized CobbleRaidsConfig load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (!Files.exists(CONFIG_PATH)) {
                CURRENT = CobbleRaidsConfig.defaults();
                write(CURRENT);
                RaidLog.info("Created default config: " + CONFIG_PATH);
                return CURRENT;
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            CURRENT = CobbleRaidsConfig.fromJson(root);

            // Migrate the file forward. Every unknown key already falls back to its default, so an
            // older config loads correctly -- but it would never gain the new settings, leaving an
            // operator with no way to discover a feature short of reading a changelog. Writing the
            // canonical form back keeps their existing values (those were just parsed into CURRENT)
            // and adds whatever this build knows about. CobbleRaidsConfigRoundTripTest pins the
            // round trip, because a key that toJson forgets would be silently erased here.
            JsonObject canonical = CURRENT.toJson();
            if (!canonical.equals(root)) {
                write(CURRENT);
                RaidLog.info("Updated " + CONFIG_PATH + " with settings new to this version.");
            }
            return CURRENT;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load CobbleRaids config " + CONFIG_PATH, ex);
        }
    }

    public static synchronized CobbleRaidsConfig reload() { return load(); }

    private static void write(CobbleRaidsConfig config) throws Exception {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(config.toJson(), writer);
        }
    }
}
