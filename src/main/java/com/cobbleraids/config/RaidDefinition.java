package com.cobbleraids.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** Immutable server-side definition for one raid species/event. */
public record RaidDefinition(
        ResourceLocation id,
        ResourceLocation species,
        RaidRarityTier rarityTier,
        int level,
        long baseHealth,
        Spawn spawn,
        Recruitment recruitment,
        Scaling scaling,
        int timeLimitSeconds,
        boolean allowFlee,
        Rewards rewards
) {
    /** Natural spawning is opt-in. Empty biome lists mean any biome in an allowed dimension. */
    public record Spawn(boolean enabled, int weight, List<ResourceLocation> dimensions,
                        List<ResourceLocation> biomes, List<ResourceLocation> biomeTags,
                        List<SpawnTime> times, int cooldownSeconds, int despawnSeconds, int maxConcurrent) {
        public Spawn {
            if (weight < 1 || weight > 1_000_000) throw new IllegalArgumentException("spawn.weight must be 1..1000000");
            dimensions = List.copyOf(dimensions);
            biomes = List.copyOf(biomes);
            biomeTags = List.copyOf(biomeTags);
            times = times.isEmpty() ? List.of(SpawnTime.ALL_DAY) : List.copyOf(times);
            if (cooldownSeconds < 0 || cooldownSeconds > 604_800) throw new IllegalArgumentException("spawn.cooldown_seconds must be 0..604800");
            if (despawnSeconds < 1 || despawnSeconds > 86_400) throw new IllegalArgumentException("spawn.despawn_seconds must be 1..86400");
            if (maxConcurrent < 1 || maxConcurrent > 256) throw new IllegalArgumentException("spawn.max_concurrent must be 1..256");
        }
        public boolean allowsDimension(ResourceLocation dimension) { return dimensions.isEmpty() || dimensions.contains(dimension); }
        public boolean allowsTime(long dayTime) {
            SpawnTime current = SpawnTime.current(dayTime);
            return times.contains(SpawnTime.ALL_DAY) || times.contains(current);
        }
    }

    public enum SpawnTime {
        ALL_DAY, EARLY_MORNING, MORNING, NOON, AFTERNOON, DUSK, NIGHT, MIDNIGHT;
        public static SpawnTime current(long dayTime) {
            long time = Math.floorMod(dayTime, 24_000L);
            if (time < 3_000L) return EARLY_MORNING;
            if (time < 6_000L) return MORNING;
            if (time < 12_000L) return NOON;
            if (time < 15_000L) return AFTERNOON;
            if (time < 18_000L) return DUSK;
            if (time < 21_000L) return NIGHT;
            return MIDNIGHT;
        }
        public static SpawnTime parse(String value) { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
    }

    /** Recruitment is separate from combat. maxPlayers is a validated transport safety cap. */
    public record Recruitment(int durationSeconds, double radius, int maxPlayers) {
        public Recruitment {
            if (durationSeconds < 1 || durationSeconds > 600) throw new IllegalArgumentException("recruitment.duration_seconds must be 1..600");
            if (!(radius > 0.0) || radius > 128.0) throw new IllegalArgumentException("recruitment.radius must be > 0 and <= 128");
            if (maxPlayers < 1 || maxPlayers > CobbleRaidsConfig.VALIDATED_MAX_HUMAN_PLAYERS)
                throw new IllegalArgumentException("recruitment.max_players must be 1.." + CobbleRaidsConfig.VALIDATED_MAX_HUMAN_PLAYERS);
        }
    }

    public record Scaling(double healthPerExtraPlayer) {
        public Scaling {
            if (healthPerExtraPlayer < 0.0 || healthPerExtraPlayer > 10.0)
                throw new IllegalArgumentException("scaling.health_per_extra_player must be 0..10");
        }
    }

    /** One item grant. chance is used by optional choice rewards; weight is used by contribution pools. */
    public record RewardItem(ResourceLocation item, int amount, double chance, int weight) {
        public RewardItem {
            Objects.requireNonNull(item, "item");
            if (amount < 1 || amount > 64_000) throw new IllegalArgumentException("reward amount must be 1..64000");
            if (chance < 0.0 || chance > 1.0) throw new IllegalArgumentException("reward chance must be 0..1");
            if (weight < 1 || weight > 1_000_000) throw new IllegalArgumentException("reward weight must be 1..1000000");
        }
    }

    /** A GUI choice is authoritative server-side; SkiesGUIs only sends the choice id back to CobbleRaids. */
    public record RewardChoice(String id, List<RewardItem> items, List<RewardItem> chanceItems) {
        public RewardChoice {
            if (id == null || !id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("reward choice ids must match [a-z0-9_.-]+");
            items = List.copyOf(items);
            chanceItems = List.copyOf(chanceItems);
            if (items.isEmpty() && chanceItems.isEmpty()) throw new IllegalArgumentException("reward choice " + id + " has no rewards");
        }
    }

    public record ContributionTier(double minPercentage, int bonusRolls) {
        public ContributionTier {
            if (minPercentage < 0.0 || minPercentage > 100.0) throw new IllegalArgumentException("contribution min_percentage must be 0..100");
            if (bonusRolls < 0 || bonusRolls > 100) throw new IllegalArgumentException("contribution bonus_rolls must be 0..100");
        }
    }

    public record ContributionBonus(boolean enabled, List<ContributionTier> tiers, List<RewardItem> pool) {
        public ContributionBonus {
            List<ContributionTier> sorted = new ArrayList<>(tiers);
            sorted.sort(Comparator.comparingDouble(ContributionTier::minPercentage));
            tiers = List.copyOf(sorted);
            pool = List.copyOf(pool);
            if (enabled && !tiers.isEmpty() && pool.isEmpty())
                throw new IllegalArgumentException("enabled contribution bonuses with tiers require a non-empty pool");
        }
    }

    public record Rewards(String guiId, Map<String, RewardChoice> choices,
                          ContributionBonus contributionBonus, List<ResourceLocation> lootTables) {
        public Rewards {
            if (guiId == null || guiId.isBlank()) throw new IllegalArgumentException("rewards.gui_id cannot be blank");
            choices = Collections.unmodifiableMap(new LinkedHashMap<>(choices));
            Objects.requireNonNull(contributionBonus, "contributionBonus");
            lootTables = List.copyOf(lootTables);
        }
    }

    public RaidDefinition {
        if (level < 1 || level > 100) throw new IllegalArgumentException("level must be 1..100");
        if (baseHealth < 1) throw new IllegalArgumentException("base_health must be >= 1");
        Objects.requireNonNull(rarityTier, "rarityTier");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(recruitment, "recruitment");
        Objects.requireNonNull(scaling, "scaling");
        Objects.requireNonNull(rewards, "rewards");
        if (timeLimitSeconds < 0) throw new IllegalArgumentException("time_limit_seconds must be >= 0");
    }

    public long scaledHealth(int participantCount) {
        if (participantCount < 1) throw new IllegalArgumentException("participantCount must be >= 1");
        double multiplier = 1.0 + scaling.healthPerExtraPlayer() * (participantCount - 1);
        double result = Math.ceil(baseHealth * multiplier);
        if (!Double.isFinite(result) || result >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) result);
    }

    public static RaidDefinition fromJson(ResourceLocation id, JsonObject root) {
        CobbleRaidsConfig global = CobbleRaidsConfigManager.get();
        ResourceLocation species = ResourceLocation.parse(requireString(root, "species"));
        int level = requireInt(root, "level");
        long baseHealth = root.has("base_health") ? root.get("base_health").getAsLong() : requireLong(root, "health");

        JsonObject spawnObject = object(root, "spawn");
        boolean spawnEnabled = spawnObject.has("enabled") && spawnObject.get("enabled").getAsBoolean();
        int spawnWeight = integer(spawnObject, "weight", 100);
        List<ResourceLocation> dimensions = readResourceLocations(spawnObject, "dimensions");
        if (dimensions.isEmpty()) dimensions = List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        List<ResourceLocation> biomes = readResourceLocations(spawnObject, "biomes");
        List<ResourceLocation> biomeTags = readResourceLocations(spawnObject, "biome_tags");
        List<SpawnTime> times = readSpawnTimes(spawnObject, "times");
        int cooldown = integer(spawnObject, "cooldown_seconds", global.naturalSpawning().defaultDefinitionCooldownSeconds());
        int despawn = integer(spawnObject, "despawn_seconds", global.naturalSpawning().defaultDespawnSeconds());
        int maxConcurrent = integer(spawnObject, "max_concurrent", 1);
        Spawn spawn = new Spawn(spawnEnabled, spawnWeight, dimensions, biomes, biomeTags, times, cooldown, despawn, maxConcurrent);
        RaidRarityTier rarityTier = root.has("rarity_tier")
                ? RaidRarityTier.parse(root.get("rarity_tier").getAsString())
                : legacyTier(spawnWeight);

        CobbleRaidsConfig.RecruitmentDefaults rd = global.recruitmentDefaults();
        JsonObject recruitmentObject = object(root, "recruitment");
        int duration = integer(recruitmentObject, "duration_seconds", rd.durationSeconds());
        double radius = decimal(recruitmentObject, "radius", rd.radius());
        int maxPlayers = recruitmentObject.has("max_players") ? recruitmentObject.get("max_players").getAsInt()
                : (root.has("max_players") ? root.get("max_players").getAsInt() : rd.maxPlayers());

        JsonObject scalingObject = object(root, "scaling");
        double healthPerExtra = decimal(scalingObject, "health_per_extra_player", 0.65);
        CobbleRaidsConfig.CombatDefaults cd = global.combatDefaults();
        int timeLimit = integer(root, "time_limit_seconds", cd.timeLimitSeconds());
        boolean allowFlee = root.has("allow_flee") ? root.get("allow_flee").getAsBoolean() : cd.allowFlee();

        Rewards rewards = parseRewards(object(root, "rewards"));
        return new RaidDefinition(id, species, rarityTier, level, baseHealth, spawn,
                new Recruitment(duration, radius, maxPlayers), new Scaling(healthPerExtra),
                timeLimit, allowFlee, rewards);
    }

    private static Rewards parseRewards(JsonObject rewards) {
        String guiId = string(rewards, "gui_id", "cobbleraids_reward");
        List<ResourceLocation> loot = readResourceLocations(rewards, "loot_tables");
        Map<String, RewardChoice> choices = new LinkedHashMap<>();
        JsonObject choiceObject = object(rewards, "choices");
        for (Map.Entry<String, JsonElement> entry : choiceObject.entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("rewards.choices." + entry.getKey() + " must be an object");
            JsonObject choice = entry.getValue().getAsJsonObject();
            choices.put(entry.getKey(), new RewardChoice(entry.getKey(),
                    readRewardItems(choice, "items", 1.0, 1),
                    readRewardItems(choice, "chance_items", -1.0, 1)));
        }

        JsonObject contribution = object(rewards, "contribution_bonus");
        boolean enabled = contribution.has("enabled") && contribution.get("enabled").getAsBoolean();
        List<ContributionTier> tiers = new ArrayList<>();
        JsonArray tierArray = array(contribution, "tiers");
        for (JsonElement element : tierArray) {
            JsonObject tier = element.getAsJsonObject();
            tiers.add(new ContributionTier(tier.get("min_percentage").getAsDouble(), tier.get("bonus_rolls").getAsInt()));
        }
        List<RewardItem> pool = readRewardItems(contribution, "pool", 1.0, -1);
        return new Rewards(guiId, choices, new ContributionBonus(enabled, tiers, pool), loot);
    }

    private static List<RewardItem> readRewardItems(JsonObject object, String key, double defaultChance, int defaultWeight) {
        List<RewardItem> values = new ArrayList<>();
        JsonArray array = array(object, key);
        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            ResourceLocation id = ResourceLocation.parse(requireString(item, "item"));
            int amount = integer(item, "amount", 1);
            double chance = defaultChance < 0 ? decimal(item, "chance", 1.0) : decimal(item, "chance", defaultChance);
            int weight = defaultWeight < 0 ? integer(item, "weight", 1) : integer(item, "weight", defaultWeight);
            values.add(new RewardItem(id, amount, chance, weight));
        }
        return values;
    }

    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }
    private static JsonArray array(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }
    private static int integer(JsonObject root, String key, int fallback) { return root.has(key) ? root.get(key).getAsInt() : fallback; }
    private static double decimal(JsonObject root, String key, double fallback) { return root.has(key) ? root.get(key).getAsDouble() : fallback; }
    private static String string(JsonObject root, String key, String fallback) { return root.has(key) ? root.get(key).getAsString() : fallback; }

    private static List<ResourceLocation> readResourceLocations(JsonObject object, String key) {
        List<ResourceLocation> values = new ArrayList<>();
        if (!object.has(key)) return values;
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) values.add(ResourceLocation.parse(element.getAsString()));
        else if (element.isJsonArray()) for (JsonElement value : element.getAsJsonArray()) values.add(ResourceLocation.parse(value.getAsString()));
        return values;
    }
    private static List<SpawnTime> readSpawnTimes(JsonObject object, String key) {
        List<SpawnTime> values = new ArrayList<>();
        if (!object.has(key)) return values;
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) values.add(SpawnTime.parse(element.getAsString()));
        else if (element.isJsonArray()) for (JsonElement value : element.getAsJsonArray()) values.add(SpawnTime.parse(value.getAsString()));
        return values;
    }
    private static String requireString(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing required string: " + key);
        return root.get(key).getAsString();
    }
    private static int requireInt(JsonObject root, String key) {
        if (!root.has(key)) throw new IllegalArgumentException("Missing required integer: " + key);
        return root.get(key).getAsInt();
    }
    private static long requireLong(JsonObject root, String key) {
        if (!root.has(key)) throw new IllegalArgumentException("Missing required long: " + key);
        return root.get(key).getAsLong();
    }

    /** Compatibility fallback for custom definitions created before Phase 31. */
    private static RaidRarityTier legacyTier(int spawnWeight) {
        if (spawnWeight >= 5) return RaidRarityTier.STARTER;
        if (spawnWeight >= 3) return RaidRarityTier.POWERHOUSE;
        return RaidRarityTier.LEGENDARY;
    }
}
