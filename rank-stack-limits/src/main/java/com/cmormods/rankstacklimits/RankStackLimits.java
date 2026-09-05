package com.cmormods.rankstacklimits;

import net.fabricmc.api.ModInitializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RankStackLimits implements ModInitializer {
    public static final String MOD_ID = "rankstacklimits";
    public static final String STACK_LIMIT_META_KEY = "stack-limit";
    public static final int VANILLA_DEFAULT_LIMIT = 64;
    public static final int V1_MAX_LIMIT = 99;

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static LuckPerms luckPerms;

    @Override
    public void onInitialize() {
        luckPerms = LuckPermsProvider.get();
        LOGGER.info("Rank Stack Limits phase 1 initialized with LuckPerms API {}-{} stack policy.",
                VANILLA_DEFAULT_LIMIT, V1_MAX_LIMIT);
    }

    public static LuckPerms luckPerms() {
        if (luckPerms == null) {
            throw new IllegalStateException("Rank Stack Limits has not initialized yet");
        }
        return luckPerms;
    }
}
