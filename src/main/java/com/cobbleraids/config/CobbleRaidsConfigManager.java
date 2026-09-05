package com.cobbleraids.config;

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
                System.out.println("[CobbleRaids] Created default config: " + CONFIG_PATH);
                return CURRENT;
            }
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                CURRENT = CobbleRaidsConfig.fromJson(root);
                return CURRENT;
            }
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
