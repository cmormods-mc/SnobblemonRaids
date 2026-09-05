package com.cmormods.rankstacklimits.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record RankStackLimitsConfig(
        int defaultStackLimit,
        int maximumStackLimit,
        String luckPermsMetaKey,
        boolean preserveVanillaUnstackables
) {
    public static final int DEFAULT_LIMIT = 64;
    public static final int V1_MAX_LIMIT = 99;
    public static final String DEFAULT_META_KEY = "stack-limit";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("rankstacklimits.json");

    public static RankStackLimitsConfig defaults() {
        return new RankStackLimitsConfig(DEFAULT_LIMIT, V1_MAX_LIMIT, DEFAULT_META_KEY, true);
    }

    public static RankStackLimitsConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                RankStackLimitsConfig defaults = defaults();
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
                return defaults;
            }

            RankStackLimitsConfig loaded = GSON.fromJson(Files.readString(CONFIG_PATH), RankStackLimitsConfig.class);
            if (loaded == null) {
                return defaults();
            }
            return sanitize(loaded);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, exception);
        }
    }

    static RankStackLimitsConfig sanitize(RankStackLimitsConfig config) {
        int defaultLimit = Math.clamp(config.defaultStackLimit(), DEFAULT_LIMIT, V1_MAX_LIMIT);
        int maxLimit = Math.clamp(config.maximumStackLimit(), defaultLimit, V1_MAX_LIMIT);
        String metaKey = config.luckPermsMetaKey() == null || config.luckPermsMetaKey().isBlank()
                ? DEFAULT_META_KEY
                : config.luckPermsMetaKey().trim();
        return new RankStackLimitsConfig(defaultLimit, maxLimit, metaKey, config.preserveVanillaUnstackables());
    }
}
