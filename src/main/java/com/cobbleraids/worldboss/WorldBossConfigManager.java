package com.cobbleraids.worldboss;

import com.cobbleraids.config.JsonFileStore;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Loads {@code config/cobbleraids/world_boss.json}, keeping the last good copy if a reload fails. */
public final class WorldBossConfigManager {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve(WorldBossConfig.FILE_NAME);

    private static volatile WorldBossConfig CURRENT = WorldBossConfig.defaults();

    private WorldBossConfigManager() {}

    public static WorldBossConfig get() { return CURRENT; }

    public static Path path() { return PATH; }

    public static synchronized WorldBossConfig load() {
        WorldBossConfig config = JsonFileStore.load(PATH, "world boss config", WorldBossConfig::defaults,
                WorldBossConfig::fromJson, WorldBossConfig::toJson);
        CURRENT = config;
        return config;
    }

    public static synchronized WorldBossConfig reload() { return load(); }
}
