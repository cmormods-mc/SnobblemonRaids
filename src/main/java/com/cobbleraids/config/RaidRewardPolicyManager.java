package com.cobbleraids.config;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the reward policy from config/cobbleraids/reward_policy.json.
 *
 * <p>Separate from server.json on purpose. This file decides what a raid win is worth, which is the
 * thing an operator tunes most often and the thing they are most likely to get wrong; keeping it
 * apart means a bad edit here cannot take the rest of the configuration down with it, and the
 * migration write-back stays a small diff an operator can actually read.
 *
 * <p>A malformed file keeps the last known good policy rather than replacing it, the same way
 * CobbleRaidsConfigManager does: an unreadable policy must not stop raids paying out.
 */
public final class RaidRewardPolicyManager {
    private static final Path POLICY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("reward_policy.json");
    private static volatile RaidRewardPolicy CURRENT = RaidRewardPolicy.defaults();

    private RaidRewardPolicyManager() {}

    public static RaidRewardPolicy get() { return CURRENT; }
    public static Path path() { return POLICY_PATH; }

    public static synchronized RaidRewardPolicy load() {
        CURRENT = JsonFileStore.load(POLICY_PATH, "reward policy", RaidRewardPolicy::defaults,
                RaidRewardPolicy::fromJson, RaidRewardPolicy::toJson);
        return CURRENT;
    }

    public static synchronized RaidRewardPolicy reload() { return load(); }
}
