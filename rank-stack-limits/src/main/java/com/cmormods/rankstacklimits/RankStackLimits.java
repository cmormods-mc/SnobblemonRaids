package com.cmormods.rankstacklimits;

import com.cmormods.rankstacklimits.config.RankStackLimitsConfig;
import com.cmormods.rankstacklimits.policy.LuckPermsStackLimitResolver;
import net.fabricmc.api.ModInitializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RankStackLimits implements ModInitializer {
    public static final String MOD_ID = "rankstacklimits";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static LuckPerms luckPerms;
    private static RankStackLimitsConfig config;
    private static LuckPermsStackLimitResolver resolver;

    @Override
    public void onInitialize() {
        luckPerms = LuckPermsProvider.get();
        config = RankStackLimitsConfig.load();
        resolver = new LuckPermsStackLimitResolver(luckPerms, config);

        LOGGER.info("Rank Stack Limits phase 2 initialized: default={}, max={}, metaKey={}, preserveVanillaUnstackables={}",
                config.defaultStackLimit(), config.maximumStackLimit(), config.luckPermsMetaKey(), config.preserveVanillaUnstackables());
    }

    public static LuckPermsStackLimitResolver resolver() {
        if (resolver == null) {
            throw new IllegalStateException("Rank Stack Limits has not initialized yet");
        }
        return resolver;
    }

    public static RankStackLimitsConfig config() {
        if (config == null) {
            throw new IllegalStateException("Rank Stack Limits has not initialized yet");
        }
        return config;
    }
}
