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
        String bossSpecialtyTable,
        String keyFragmentTable,
        List<Integer> keyFragmentsByBonusRolls
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
        requireToken(keyFragmentTable, TIER_TOKEN, "key_fragment_table");
        keyFragmentsByBonusRolls = validateFragments(keyFragmentsByBonusRolls);
    }

    public static RaidRewardPolicy defaults() {
        return new RaidRewardPolicy(
                CURRENT_VERSION,
                2,
                // Flat: every claim is one specialty and two general selections, three items, and
                // contribution no longer adds any. A single threshold at 0% is how "no bonus" is
                // spelled, because an empty list falls back to the defaults rather than meaning none.
                List.of(new ContributionMath.Threshold(0.0, 0)),
                "cobbleraids:general/" + TIER_TOKEN,
                "cobbleraids:specialty/" + TIER_TOKEN,
                "cobbleraids:specialty/boss/" + SPECIES_TOKEN,
                "cobbleraids:keys/" + TIER_TOKEN,
                // No guaranteed fragments. They are a category inside the general tables now, so
                // they compete for one of the three slots; see build_tables.GENERAL_CATEGORIES for
                // the per-tier rates that make a key take 6/9/12/15 raids on average.
                List.of(0, 0, 0, 0));
    }

    /** The shipped policy before flat three-slot claims: the only file the migration rewrites. */
    private static boolean isSupersededDefault(int standardRolls,
                                               List<ContributionMath.Threshold> thresholds,
                                               List<Integer> fragments) {
        return standardRolls == 2
                && thresholds.equals(List.of(new ContributionMath.Threshold(20.0, 1),
                        new ContributionMath.Threshold(35.0, 2),
                        new ContributionMath.Threshold(50.0, 3)))
                && fragments.equals(List.of(1, 2, 2, 3));
    }

    public String keyFragmentTableFor(RaidRarityTier tier) {
        return keyFragmentTable.replace(TIER_TOKEN, tier.serializedName());
    }

    /** How many fragments a claim grants when the player earned {@code bonusRolls} bonus rolls. */
    public int keyFragmentsFor(int bonusRolls) {
        int index = Math.clamp(bonusRolls, 0, keyFragmentsByBonusRolls.size() - 1);
        return keyFragmentsByBonusRolls.get(index);
    }

    /**
     * One entry per reachable bonus-roll count, none negative and none smaller than the one below.
     *
     * <p>Descending would mean contributing more earned fewer fragments, which is the kind of
     * inversion a hand-edited config produces and nobody notices until a player reports it.
     */
    private static List<Integer> validateFragments(List<Integer> counts) {
        if (counts == null || counts.size() != MAX_BONUS_ROLLS + 1)
            throw new IllegalArgumentException("reward_policy.key_fragments_by_bonus_rolls must have "
                    + (MAX_BONUS_ROLLS + 1) + " entries, one per bonus-roll count 0.." + MAX_BONUS_ROLLS);
        int previous = 0;
        for (int count : counts) {
            if (count < 0)
                throw new IllegalArgumentException("reward_policy.key_fragments_by_bonus_rolls cannot be negative");
            if (count < previous)
                throw new IllegalArgumentException(
                        "reward_policy.key_fragments_by_bonus_rolls must not decrease: " + counts);
            previous = count;
        }
        return List.copyOf(counts);
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

        int standardRolls = Json.integer(root, "standard_general_rolls", defaults.standardGeneralRolls());
        List<Integer> fragments = fragments(root, defaults);
        // An operator who never touched the old shipped file gets the new shipped policy; one who
        // changed anything keeps exactly what they wrote. Comparing to the old defaults is the only
        // way to tell the two apart, since the file carries no record of who wrote it.
        if (isSupersededDefault(standardRolls, thresholds, fragments)) {
            thresholds = defaults.contributionThresholds();
            fragments = defaults.keyFragmentsByBonusRolls();
        }

        JsonObject tables = Json.object(root, "tables");
        return new RaidRewardPolicy(
                Json.integer(root, "version", defaults.version()),
                standardRolls,
                thresholds,
                Json.string(tables, "general", defaults.generalTable()),
                Json.string(tables, "specialty", defaults.specialtyTable()),
                Json.string(tables, "boss_specialty", defaults.bossSpecialtyTable()),
                Json.string(tables, "key_fragment", defaults.keyFragmentTable()),
                fragments);
    }

    /**
     * Missing or malformed leaves the default. A file written before key fragments existed has no
     * such array, and an operator who deletes it means "as shipped", not "no fragments" -- so this
     * is one of the places where falling back beats refusing the file.
     */
    private static List<Integer> fragments(JsonObject root, RaidRewardPolicy defaults) {
        if (!root.has("key_fragments_by_bonus_rolls")) return defaults.keyFragmentsByBonusRolls();
        List<Integer> counts = new ArrayList<>();
        for (JsonElement element : Json.array(root, "key_fragments_by_bonus_rolls")) {
            counts.add(element.getAsInt());
        }
        return counts.isEmpty() ? defaults.keyFragmentsByBonusRolls() : counts;
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
        tables.addProperty("key_fragment", keyFragmentTable);
        root.add("tables", tables);
        JsonArray fragments = new JsonArray();
        for (int count : keyFragmentsByBonusRolls) fragments.add(count);
        root.add("key_fragments_by_bonus_rolls", fragments);
        return root;
    }
}
