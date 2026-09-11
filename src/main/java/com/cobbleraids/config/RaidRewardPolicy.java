package com.cobbleraids.config;

import com.cobbleraids.reward.ContributionMath;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Server-wide reward policy: how many selections a claim is worth, what contribution earns, and
 * which loot tables those selections come from.
 *
 * <p>This exists so the answer lives in one place instead of in 130 raid definitions. A definition
 * that names no loot tables of its own is <em>policy-driven</em>: the tables come from here, keyed
 * by its rarity tier and, where one exists, its species. A definition that does name tables keeps
 * its own behaviour and ignores all of this -- see RewardPlanResolver, which is the only thing that
 * decides which of the two a definition is.
 *
 * <p>Kept free of Minecraft types, table ids included, so the whole policy and the resolver that
 * reads it can be unit-tested without a server.
 */
public record RaidRewardPolicy(
        int version,
        int standardGeneralRolls,
        List<ContributionMath.Threshold> contributionThresholds,
        String generalTable,
        String specialtyTable,
        String bossSpecialtyTable
) {
    /** Bumped when the schema changes in a way an older file cannot be read as. */
    public static final int CURRENT_VERSION = 1;

    /** Placeholders the table patterns are required to carry, so a typo cannot silently resolve. */
    public static final String TIER_TOKEN = "<tier>";
    public static final String SPECIES_TOKEN = "<species>";

    private static final int MAX_BONUS_ROLLS = 3;

    public RaidRewardPolicy {
        if (version != CURRENT_VERSION)
            throw new IllegalArgumentException("reward_policy.version must be " + CURRENT_VERSION
                    + ", got " + version);
        if (standardGeneralRolls < 1 || standardGeneralRolls > 8)
            throw new IllegalArgumentException("reward_policy.standard_general_rolls must be 1..8");
        contributionThresholds = validateThresholds(contributionThresholds);
        requireToken(generalTable, TIER_TOKEN, "general_table");
        requireToken(specialtyTable, TIER_TOKEN, "specialty_table");
        requireToken(bossSpecialtyTable, SPECIES_TOKEN, "boss_specialty_table");
    }

    public static RaidRewardPolicy defaults() {
        return new RaidRewardPolicy(
                CURRENT_VERSION,
                2,
                List.of(new ContributionMath.Threshold(20.0, 1),
                        new ContributionMath.Threshold(35.0, 2),
                        new ContributionMath.Threshold(50.0, 3)),
                "cobbleraids:general/" + TIER_TOKEN,
                "cobbleraids:specialty/" + TIER_TOKEN,
                "cobbleraids:specialty/boss/" + SPECIES_TOKEN);
    }

    public String generalTableFor(RaidRarityTier tier) {
        return generalTable.replace(TIER_TOKEN, tier.serializedName());
    }

    public String specialtyTableFor(RaidRarityTier tier) {
        return specialtyTable.replace(TIER_TOKEN, tier.serializedName());
    }

    /** The boss-specific specialty table id, which may or may not exist; the resolver checks. */
    public String bossSpecialtyTableFor(String species) {
        return bossSpecialtyTable.replace(SPECIES_TOKEN, species.toLowerCase(Locale.ROOT));
    }

    /**
     * Thresholds must be unique, ascending, and award no more each step than the step above.
     *
     * <p>The uniqueness rule is not fussiness. ContributionMath.bonusRolls keeps the highest
     * matching threshold and breaks ties by whichever it saw last, so two entries at the same
     * percentage make the awarded count depend on file order -- a bug nobody would find by reading
     * the config.
     */
    private static List<ContributionMath.Threshold> validateThresholds(List<ContributionMath.Threshold> thresholds) {
        List<ContributionMath.Threshold> sorted = new ArrayList<>(thresholds);
        sorted.sort(Comparator.comparingDouble(ContributionMath.Threshold::minPercentage));
        double previousPercentage = -1.0;
        int previousRolls = 0;
        for (ContributionMath.Threshold threshold : sorted) {
            if (threshold.minPercentage() < 0.0 || threshold.minPercentage() > 100.0)
                throw new IllegalArgumentException("reward_policy contribution threshold percentages must be 0..100");
            if (threshold.minPercentage() == previousPercentage)
                throw new IllegalArgumentException("reward_policy has two contribution thresholds at "
                        + threshold.minPercentage() + "%; which one applies would depend on file order");
            if (threshold.rolls() < 0 || threshold.rolls() > MAX_BONUS_ROLLS)
                throw new IllegalArgumentException("reward_policy contribution bonus_rolls must be 0.."
                        + MAX_BONUS_ROLLS);
            if (threshold.rolls() < previousRolls)
                throw new IllegalArgumentException("reward_policy threshold at " + threshold.minPercentage()
                        + "% awards fewer rolls than a lower one, so contributing more would pay less");
            previousPercentage = threshold.minPercentage();
            previousRolls = threshold.rolls();
        }
        return List.copyOf(sorted);
    }

    private static void requireToken(String pattern, String token, String field) {
        if (pattern == null || pattern.isBlank())
            throw new IllegalArgumentException("reward_policy." + field + " cannot be blank");
        if (!pattern.contains(token))
            throw new IllegalArgumentException("reward_policy." + field + " must contain " + token
                    + ", otherwise every tier would roll the same table");
    }

    public static RaidRewardPolicy fromJson(JsonObject root) {
        RaidRewardPolicy defaults = defaults();
        List<ContributionMath.Threshold> thresholds = new ArrayList<>();
        JsonArray array = Json.array(root, "contribution_thresholds");
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            thresholds.add(new ContributionMath.Threshold(
                    Json.decimal(entry, "min_percentage", 0.0),
                    Json.integer(entry, "bonus_rolls", 0)));
        }
        if (thresholds.isEmpty()) thresholds = defaults.contributionThresholds();

        JsonObject tables = Json.object(root, "tables");
        return new RaidRewardPolicy(
                Json.integer(root, "version", defaults.version()),
                Json.integer(root, "standard_general_rolls", defaults.standardGeneralRolls()),
                thresholds,
                Json.string(tables, "general", defaults.generalTable()),
                Json.string(tables, "specialty", defaults.specialtyTable()),
                Json.string(tables, "boss_specialty", defaults.bossSpecialtyTable()));
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("standard_general_rolls", standardGeneralRolls);
        JsonArray array = new JsonArray();
        for (ContributionMath.Threshold threshold : contributionThresholds) {
            JsonObject entry = new JsonObject();
            entry.addProperty("min_percentage", threshold.minPercentage());
            entry.addProperty("bonus_rolls", threshold.rolls());
            array.add(entry);
        }
        root.add("contribution_thresholds", array);
        JsonObject tables = new JsonObject();
        tables.addProperty("general", generalTable);
        tables.addProperty("specialty", specialtyTable);
        tables.addProperty("boss_specialty", bossSpecialtyTable);
        root.add("tables", tables);
        return root;
    }
}
