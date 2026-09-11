package com.cobbleraids.config;

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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path POLICY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("cobbleraids").resolve("reward_policy.json");
    private static volatile RaidRewardPolicy CURRENT = RaidRewardPolicy.defaults();

    private RaidRewardPolicyManager() {}

    public static RaidRewardPolicy get() { return CURRENT; }
    public static Path path() { return POLICY_PATH; }

    public static synchronized RaidRewardPolicy load() {
        try {
            Files.createDirectories(POLICY_PATH.getParent());
            if (!Files.exists(POLICY_PATH)) {
                CURRENT = RaidRewardPolicy.defaults();
                write(CURRENT);
                RaidLog.info("Created default reward policy: " + POLICY_PATH);
                return CURRENT;
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(POLICY_PATH, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            CURRENT = RaidRewardPolicy.fromJson(root);

            JsonObject canonical = CURRENT.toJson();
            if (!canonical.equals(root)) {
                write(CURRENT);
                RaidLog.info("Updated " + POLICY_PATH + " with settings new to this version.");
            }
            return CURRENT;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load reward policy " + POLICY_PATH, ex);
        }
    }

    public static synchronized RaidRewardPolicy reload() { return load(); }

    private static void write(RaidRewardPolicy policy) throws Exception {
        try (Writer writer = Files.newBufferedWriter(POLICY_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(policy.toJson(), writer);
        }
    }
}
