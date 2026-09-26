package com.cobbleraids.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Loads the operator-facing config from config/cobbleraids/server.json. */
public final class CobbleRaidsConfigManager {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("server.json");
    private static volatile CobbleRaidsConfig CURRENT = CobbleRaidsConfig.defaults();

    private CobbleRaidsConfigManager() {}

    public static CobbleRaidsConfig get() { return CURRENT; }
    public static Path path() { return CONFIG_PATH; }

    // The round trip (migrate-forward, gaining settings new to this version without losing an
    // operator's existing values) is pinned by CobbleRaidsConfigRoundTripTest, because a key that
    // toJson forgets would be silently erased by JsonFileStore's migration write-back.
    public static synchronized CobbleRaidsConfig load() {
        CURRENT = JsonFileStore.load(CONFIG_PATH, "config", CobbleRaidsConfig::defaults,
                CobbleRaidsConfig::fromJson, CobbleRaidsConfig::toJson);
        return CURRENT;
    }

    public static synchronized CobbleRaidsConfig reload() { return load(); }
}
