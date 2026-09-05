package com.cmormods.rankstacklimits.policy;

import com.cmormods.rankstacklimits.config.RankStackLimitsConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class LuckPermsStackLimitResolver {
    private final LuckPerms luckPerms;
    private final RankStackLimitsConfig config;

    public LuckPermsStackLimitResolver(LuckPerms luckPerms, RankStackLimitsConfig config) {
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
        this.config = Objects.requireNonNull(config, "config");
    }

    public int resolve(ServerPlayer player) {
        User user = luckPerms.getUserManager().getUser(player.getUUID());
        if (user == null) {
            return config.defaultStackLimit();
        }

        String rawValue = user.getCachedData().getMetaData().getMetaValue(config.luckPermsMetaKey());
        return StackLimitPolicy.resolve(rawValue, config.defaultStackLimit(), config.maximumStackLimit());
    }
}
