package com.cobbleraids.config;

import com.google.gson.JsonObject;

/** Server-owner tuning values. Raid datapack JSON can override content-specific values. */
public record CobbleRaidsConfig(
        NaturalSpawning naturalSpawning,
        RecruitmentDefaults recruitmentDefaults,
        CombatDefaults combatDefaults,
        boolean debugLogging
) {
    public static final int VALIDATED_MAX_HUMAN_PLAYERS = 4;

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
            RaidTierWeights tierWeights
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
            if (defaultDespawnSeconds < 1 || defaultDespawnSeconds > 86_400)
                throw new IllegalArgumentException("natural_spawning.default_despawn_seconds must be 1..86400");
            if (defaultDefinitionCooldownSeconds < 0 || defaultDefinitionCooldownSeconds > 604_800)
                throw new IllegalArgumentException("natural_spawning.default_definition_cooldown_seconds must be 0..604800");
            if (tierWeights == null)
                throw new IllegalArgumentException("natural_spawning.tier_weights cannot be null");
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
                        64.0,
                        600,
                        1800,
                        RaidTierWeights.defaults()
                ),
                new RecruitmentDefaults(45, 10.0, VALIDATED_MAX_HUMAN_PLAYERS),
                new CombatDefaults(900, false),
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
                tierWeights
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

        return new CobbleRaidsConfig(naturalSpawning, recruitmentDefaults, combatDefaults,
                bool(root, "debug_logging", defaults.debugLogging()));
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

        root.addProperty("debug_logging", debugLogging);
        return root;
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
