package com.cobbleraids.config;

import com.google.gson.JsonObject;
import java.util.Locale;

/** Server-owner tuning values. Raid datapack JSON can override content-specific values. */
public record CobbleRaidsConfig(
        NaturalSpawning naturalSpawning,
        RecruitmentDefaults recruitmentDefaults,
        CombatDefaults combatDefaults,
        TierScaling tierScaling,
        boolean debugLogging
) {
    public static final int VALIDATED_MAX_HUMAN_PLAYERS = 4;

    /** How much location detail a natural-spawn announcement reveals to the whole server. */
    public enum AnnouncementPrecision {
        EXACT, NEAREST_HUNDRED, BIOME_ONLY, DISABLED;

        public String serializedName() { return name().toLowerCase(Locale.ROOT); }

        public static AnnouncementPrecision parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record NaturalSpawning(
            boolean enabled,
            int checkIntervalTicks,
            double spawnAttemptChance,
            int attemptsPerCheck,
            int maxActiveRaids,
            int maxActiveRaidsPerDimension,
            double minDistanceFromPlayer,
            double maxDistanceFromPlayer,
            double minDistanceBetweenRaids,
            int locationAttempts,
            double despawnPlayerRadius,
            int defaultDespawnSeconds,
            int defaultDefinitionCooldownSeconds,
            RaidTierWeights tierWeights,
            AnnouncementPrecision announcementPrecision
    ) {
        public NaturalSpawning {
            if (checkIntervalTicks < 20 || checkIntervalTicks > 72_000)
                throw new IllegalArgumentException("natural_spawning.check_interval_ticks must be 20..72000");
            if (spawnAttemptChance < 0.0 || spawnAttemptChance > 1.0)
                throw new IllegalArgumentException("natural_spawning.spawn_attempt_chance must be 0..1");
            if (attemptsPerCheck < 1 || attemptsPerCheck > 64)
                throw new IllegalArgumentException("natural_spawning.attempts_per_check must be 1..64");
            if (maxActiveRaids < 1 || maxActiveRaids > 256)
                throw new IllegalArgumentException("natural_spawning.max_active_raids must be 1..256");
            if (maxActiveRaidsPerDimension < 1 || maxActiveRaidsPerDimension > maxActiveRaids)
                throw new IllegalArgumentException("natural_spawning.max_active_raids_per_dimension must be 1..max_active_raids");
            if (!(minDistanceFromPlayer >= 8.0) || !(maxDistanceFromPlayer > minDistanceFromPlayer) || maxDistanceFromPlayer > 512.0)
                throw new IllegalArgumentException("natural spawn player distances must satisfy 8 <= min < max <= 512");
            if (minDistanceBetweenRaids < 0.0 || minDistanceBetweenRaids > 4096.0)
                throw new IllegalArgumentException("natural_spawning.min_distance_between_raids must be 0..4096");
            if (locationAttempts < 1 || locationAttempts > 128)
                throw new IllegalArgumentException("natural_spawning.location_attempts must be 1..128");
            if (despawnPlayerRadius < 1.0 || despawnPlayerRadius > 512.0)
                throw new IllegalArgumentException("natural_spawning.despawn_player_radius must be 1..512");
            // Not fatal, because existing server.json files ship the old equal values and an
            // upgrade must not refuse to boot. Wild raids are placed between min_distance_from_player
            // and max_distance_from_player, so a despawn radius at or above that ceiling means every
            // boss is born inside its own keep-alive bubble and the unattended timer never starts.
            if (despawnPlayerRadius >= maxDistanceFromPlayer)
                System.out.println("[CobbleRaids] WARNING: natural_spawning.despawn_player_radius ("
                        + despawnPlayerRadius + ") is not smaller than natural_spawning.max_distance_from_player ("
                        + maxDistanceFromPlayer + "), so wild raid bosses spawn already inside the radius that"
                        + " keeps them alive. Unattended bosses will not despawn until a player leaves that radius.");
            if (defaultDespawnSeconds < 1 || defaultDespawnSeconds > 86_400)
                throw new IllegalArgumentException("natural_spawning.default_despawn_seconds must be 1..86400");
            if (defaultDefinitionCooldownSeconds < 0 || defaultDefinitionCooldownSeconds > 604_800)
                throw new IllegalArgumentException("natural_spawning.default_definition_cooldown_seconds must be 0..604800");
            if (tierWeights == null)
                throw new IllegalArgumentException("natural_spawning.tier_weights cannot be null");
            if (announcementPrecision == null)
                throw new IllegalArgumentException("natural_spawning.announcement_precision cannot be null");
        }
    }

    public record RecruitmentDefaults(int durationSeconds, double radius, int maxPlayers) {
        public RecruitmentDefaults {
            if (durationSeconds < 1 || durationSeconds > 600)
                throw new IllegalArgumentException("recruitment_defaults.duration_seconds must be 1..600");
            if (!(radius > 0.0) || radius > 128.0)
                throw new IllegalArgumentException("recruitment_defaults.radius must be > 0 and <= 128");
            if (maxPlayers < 1 || maxPlayers > VALIDATED_MAX_HUMAN_PLAYERS)
                throw new IllegalArgumentException("recruitment_defaults.max_players must be 1.." + VALIDATED_MAX_HUMAN_PLAYERS);
        }
    }


    /** Defaults applied when an individual raid JSON omits combat rule fields. */
    public record CombatDefaults(int timeLimitSeconds, boolean allowFlee) {
        public CombatDefaults {
            if (timeLimitSeconds < 0 || timeLimitSeconds > 86_400)
                throw new IllegalArgumentException("combat_defaults.time_limit_seconds must be 0..86400");
        }
    }

    /**
     * Per-tier multipliers layered on top of each raid definition's own hand-tuned values.
     * Disabled by default: the 130 shipped definitions already carry tier-appropriate health and
     * rewards (a legendary's base_health is already higher than a starter's), so turning this on
     * compounds with that existing tuning rather than replacing it -- read the numbers in your
     * definitions before enabling, and adjust the multipliers here to match.
     */
    public record TierScaling(
            boolean enabled,
            TierMultipliers starter,
            TierMultipliers powerhouse,
            TierMultipliers legendary,
            TierMultipliers mythical
    ) {
        public TierScaling {
            if (starter == null || powerhouse == null || legendary == null || mythical == null)
                throw new IllegalArgumentException("tier_scaling multipliers cannot be null");
        }

        public TierMultipliers forTier(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        public static TierScaling defaults() {
            return new TierScaling(
                    false,
                    new TierMultipliers(1.0, 1.0, 1.0),
                    new TierMultipliers(1.15, 1.1, 1.15),
                    new TierMultipliers(1.35, 1.25, 1.35),
                    new TierMultipliers(1.5, 1.4, 1.5)
            );
        }
    }

    public record TierMultipliers(double health, double timeLimit, double reward) {
        public TierMultipliers {
            if (health < 0.1 || health > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier health must be 0.1..10");
            if (timeLimit < 0.1 || timeLimit > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier time_limit must be 0.1..10");
            if (reward < 0.1 || reward > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier reward must be 0.1..10");
        }
    }

    public static CobbleRaidsConfig defaults() {
        return new CobbleRaidsConfig(
                new NaturalSpawning(
                        true,
                        1200,
                        0.25,
                        1,
                        3,
                        2,
                        24.0,
                        64.0,
                        128.0,
                        16,
                        32.0,
                        600,
                        1800,
                        RaidTierWeights.defaults(),
                        AnnouncementPrecision.NEAREST_HUNDRED
                ),
                new RecruitmentDefaults(45, 10.0, VALIDATED_MAX_HUMAN_PLAYERS),
                new CombatDefaults(900, false),
                TierScaling.defaults(),
                false
        );
    }

    public static CobbleRaidsConfig fromJson(JsonObject root) {
        CobbleRaidsConfig defaults = defaults();

        JsonObject natural = object(root, "natural_spawning");
        NaturalSpawning nd = defaults.naturalSpawning();
        JsonObject tierObject = object(natural, "tier_weights");
        RaidTierWeights tw = nd.tierWeights();
        RaidTierWeights tierWeights = new RaidTierWeights(
                integer(tierObject, "starter", tw.starter()),
                integer(tierObject, "powerhouse", tw.powerhouse()),
                integer(tierObject, "legendary", tw.legendary()),
                integer(tierObject, "mythical", tw.mythical())
        );
        NaturalSpawning naturalSpawning = new NaturalSpawning(
                bool(natural, "enabled", nd.enabled()),
                integer(natural, "check_interval_ticks", nd.checkIntervalTicks()),
                decimal(natural, "spawn_attempt_chance", nd.spawnAttemptChance()),
                integer(natural, "attempts_per_check", nd.attemptsPerCheck()),
                integer(natural, "max_active_raids", nd.maxActiveRaids()),
                integer(natural, "max_active_raids_per_dimension", nd.maxActiveRaidsPerDimension()),
                decimal(natural, "min_distance_from_player", nd.minDistanceFromPlayer()),
                decimal(natural, "max_distance_from_player", nd.maxDistanceFromPlayer()),
                decimal(natural, "min_distance_between_raids", nd.minDistanceBetweenRaids()),
                integer(natural, "location_attempts", nd.locationAttempts()),
                decimal(natural, "despawn_player_radius", nd.despawnPlayerRadius()),
                integer(natural, "default_despawn_seconds", nd.defaultDespawnSeconds()),
                integer(natural, "default_definition_cooldown_seconds", nd.defaultDefinitionCooldownSeconds()),
                tierWeights,
                natural.has("announcement_precision")
                        ? AnnouncementPrecision.parse(natural.get("announcement_precision").getAsString())
                        : nd.announcementPrecision()
        );

        JsonObject recruitment = object(root, "recruitment_defaults");
        RecruitmentDefaults rd = defaults.recruitmentDefaults();
        RecruitmentDefaults recruitmentDefaults = new RecruitmentDefaults(
                integer(recruitment, "duration_seconds", rd.durationSeconds()),
                decimal(recruitment, "radius", rd.radius()),
                integer(recruitment, "max_players", rd.maxPlayers())
        );

        JsonObject combat = object(root, "combat_defaults");
        CombatDefaults cd = defaults.combatDefaults();
        CombatDefaults combatDefaults = new CombatDefaults(
                integer(combat, "time_limit_seconds", cd.timeLimitSeconds()),
                bool(combat, "allow_flee", cd.allowFlee())
        );

        JsonObject tierScalingObject = object(root, "tier_scaling");
        TierScaling ts = defaults.tierScaling();
        TierScaling tierScaling = new TierScaling(
                bool(tierScalingObject, "enabled", ts.enabled()),
                readTierMultipliers(tierScalingObject, "starter", ts.starter()),
                readTierMultipliers(tierScalingObject, "powerhouse", ts.powerhouse()),
                readTierMultipliers(tierScalingObject, "legendary", ts.legendary()),
                readTierMultipliers(tierScalingObject, "mythical", ts.mythical())
        );

        return new CobbleRaidsConfig(naturalSpawning, recruitmentDefaults, combatDefaults, tierScaling,
                bool(root, "debug_logging", defaults.debugLogging()));
    }

    private static TierMultipliers readTierMultipliers(JsonObject tierScaling, String key, TierMultipliers fallback) {
        JsonObject tier = object(tierScaling, key);
        return new TierMultipliers(
                decimal(tier, "health_multiplier", fallback.health()),
                decimal(tier, "time_limit_multiplier", fallback.timeLimit()),
                decimal(tier, "reward_multiplier", fallback.reward())
        );
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject natural = new JsonObject();
        natural.addProperty("enabled", naturalSpawning.enabled());
        natural.addProperty("check_interval_ticks", naturalSpawning.checkIntervalTicks());
        natural.addProperty("spawn_attempt_chance", naturalSpawning.spawnAttemptChance());
        natural.addProperty("attempts_per_check", naturalSpawning.attemptsPerCheck());
        natural.addProperty("max_active_raids", naturalSpawning.maxActiveRaids());
        natural.addProperty("max_active_raids_per_dimension", naturalSpawning.maxActiveRaidsPerDimension());
        natural.addProperty("min_distance_from_player", naturalSpawning.minDistanceFromPlayer());
        natural.addProperty("max_distance_from_player", naturalSpawning.maxDistanceFromPlayer());
        natural.addProperty("min_distance_between_raids", naturalSpawning.minDistanceBetweenRaids());
        natural.addProperty("location_attempts", naturalSpawning.locationAttempts());
        natural.addProperty("despawn_player_radius", naturalSpawning.despawnPlayerRadius());
        natural.addProperty("default_despawn_seconds", naturalSpawning.defaultDespawnSeconds());
        natural.addProperty("default_definition_cooldown_seconds", naturalSpawning.defaultDefinitionCooldownSeconds());
        JsonObject tierWeights = new JsonObject();
        tierWeights.addProperty("starter", naturalSpawning.tierWeights().starter());
        tierWeights.addProperty("powerhouse", naturalSpawning.tierWeights().powerhouse());
        tierWeights.addProperty("legendary", naturalSpawning.tierWeights().legendary());
        tierWeights.addProperty("mythical", naturalSpawning.tierWeights().mythical());
        natural.add("tier_weights", tierWeights);
        natural.addProperty("announcement_precision", naturalSpawning.announcementPrecision().serializedName());
        root.add("natural_spawning", natural);

        JsonObject recruitment = new JsonObject();
        recruitment.addProperty("duration_seconds", recruitmentDefaults.durationSeconds());
        recruitment.addProperty("radius", recruitmentDefaults.radius());
        recruitment.addProperty("max_players", recruitmentDefaults.maxPlayers());
        root.add("recruitment_defaults", recruitment);

        JsonObject combat = new JsonObject();
        combat.addProperty("time_limit_seconds", combatDefaults.timeLimitSeconds());
        combat.addProperty("allow_flee", combatDefaults.allowFlee());
        root.add("combat_defaults", combat);

        JsonObject tierScalingObject = new JsonObject();
        tierScalingObject.addProperty("enabled", tierScaling.enabled());
        tierScalingObject.add("starter", tierMultipliersJson(tierScaling.starter()));
        tierScalingObject.add("powerhouse", tierMultipliersJson(tierScaling.powerhouse()));
        tierScalingObject.add("legendary", tierMultipliersJson(tierScaling.legendary()));
        tierScalingObject.add("mythical", tierMultipliersJson(tierScaling.mythical()));
        root.add("tier_scaling", tierScalingObject);

        root.addProperty("debug_logging", debugLogging);
        return root;
    }

    private static JsonObject tierMultipliersJson(TierMultipliers multipliers) {
        JsonObject object = new JsonObject();
        object.addProperty("health_multiplier", multipliers.health());
        object.addProperty("time_limit_multiplier", multipliers.timeLimit());
        object.addProperty("reward_multiplier", multipliers.reward());
        return object;
    }

    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }
    private static int integer(JsonObject root, String key, int fallback) {
        return root.has(key) ? root.get(key).getAsInt() : fallback;
    }
    private static double decimal(JsonObject root, String key, double fallback) {
        return root.has(key) ? root.get(key).getAsDouble() : fallback;
    }
    private static boolean bool(JsonObject root, String key, boolean fallback) {
        return root.has(key) ? root.get(key).getAsBoolean() : fallback;
    }
}
