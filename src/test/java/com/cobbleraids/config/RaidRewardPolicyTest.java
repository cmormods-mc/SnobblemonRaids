package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cobbleraids.reward.ContributionMath;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy file is the thing an operator edits most and the thing they are most likely to get
 * subtly wrong, so its validation is where the value is: every rule here exists because breaking it
 * produces a raid economy that looks configured and behaves wrongly.
 */
class RaidRewardPolicyTest {

    private static RaidRewardPolicy withThresholds(List<ContributionMath.Threshold> thresholds) {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();
        return new RaidRewardPolicy(defaults.version(), defaults.standardGeneralRolls(), thresholds,
                defaults.generalTable(), defaults.specialtyTable(), defaults.bossSpecialtyTable(),
                defaults.keyFragmentTable(), defaults.keyFragmentsByBonusRolls());
    }

    @Test
    @DisplayName("defaults survive a serialize/parse round trip unchanged")
    void defaultsRoundTrip() {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();

        assertEquals(defaults, RaidRewardPolicy.fromJson(defaults.toJson()));
    }

    @Test
    @DisplayName("serialization is stable, so the migration write cannot loop")
    void serializationIsStable() {
        var once = RaidRewardPolicy.defaults().toJson();

        assertEquals(once, RaidRewardPolicy.fromJson(once).toJson());
    }

    @Test
    @DisplayName("an empty policy file is the default policy, not a policy that pays nothing")
    void emptyJsonIsDefaults() {
        assertEquals(RaidRewardPolicy.defaults(), RaidRewardPolicy.fromJson(new com.google.gson.JsonObject()));
    }

    @Test
    @DisplayName("the shipped policy is flat: contribution adds no selections and no fragments are guaranteed")
    void shippedPolicyIsFlat() {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();

        assertEquals(2, defaults.standardGeneralRolls());
        for (double share : new double[] {0.0, 20.0, 50.0, 100.0}) {
            assertEquals(0, ContributionMath.bonusRolls(share, defaults.contributionThresholds()), "at " + share);
        }
        for (int bonus = 0; bonus <= 3; bonus++) assertEquals(0, defaults.keyFragmentsFor(bonus));
    }

    private static com.google.gson.JsonObject oldShippedFile() {
        var root = RaidRewardPolicy.defaults().toJson();
        var ladder = new com.google.gson.JsonArray();
        for (double[] row : new double[][] {{20.0, 1}, {35.0, 2}, {50.0, 3}}) {
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("min_percentage", row[0]);
            entry.addProperty("bonus_rolls", (int) row[1]);
            ladder.add(entry);
        }
        root.add("contribution_thresholds", ladder);
        var fragments = new com.google.gson.JsonArray();
        for (int count : new int[] {1, 2, 2, 3}) fragments.add(count);
        root.add("key_fragments_by_bonus_rolls", fragments);
        return root;
    }

    @Test
    @DisplayName("an untouched file from before the flat policy moves to the new one")
    void supersededDefaultsMigrate() {
        assertEquals(RaidRewardPolicy.defaults(), RaidRewardPolicy.fromJson(oldShippedFile()));
    }

    @Test
    @DisplayName("a file the operator changed is left exactly as written")
    void editedFilesAreNotMigrated() {
        var edited = oldShippedFile();
        var fragments = new com.google.gson.JsonArray();
        for (int count : new int[] {1, 2, 2, 4}) fragments.add(count);
        edited.add("key_fragments_by_bonus_rolls", fragments);

        RaidRewardPolicy kept = RaidRewardPolicy.fromJson(edited);
        assertEquals(3, ContributionMath.bonusRolls(50.0, kept.contributionThresholds()));
        assertEquals(4, kept.keyFragmentsFor(3));
    }

    @Test
    @DisplayName("two thresholds at the same percentage are rejected rather than resolved by file order")
    void duplicateThresholdsAreRejected() {
        // ContributionMath keeps the highest matching threshold and breaks a tie by whichever it
        // saw last, so this would make the award depend on the order keys happen to be written in.
        assertThrows(IllegalArgumentException.class, () -> withThresholds(List.of(
                new ContributionMath.Threshold(20.0, 1),
                new ContributionMath.Threshold(20.0, 3))));
    }

    @Test
    @DisplayName("a threshold that pays less for contributing more is rejected")
    void decreasingRollsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> withThresholds(List.of(
                new ContributionMath.Threshold(20.0, 3),
                new ContributionMath.Threshold(50.0, 1))));
    }

    @Test
    @DisplayName("thresholds outside 0..100, or awarding more than three rolls, are rejected")
    void boundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> withThresholds(List.of(new ContributionMath.Threshold(101.0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> withThresholds(List.of(new ContributionMath.Threshold(-1.0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> withThresholds(List.of(new ContributionMath.Threshold(20.0, 4))));
    }

    @Test
    @DisplayName("thresholds are sorted on the way in, so an out-of-order file still behaves")
    void thresholdsAreSorted() {
        RaidRewardPolicy policy = withThresholds(List.of(
                new ContributionMath.Threshold(50.0, 3),
                new ContributionMath.Threshold(20.0, 1),
                new ContributionMath.Threshold(35.0, 2)));

        assertEquals(20.0, policy.contributionThresholds().get(0).minPercentage());
        assertEquals(50.0, policy.contributionThresholds().get(2).minPercentage());
    }

    @Test
    @DisplayName("a table pattern missing its placeholder is rejected, not silently shared by every tier")
    void tablePatternsMustCarryTheirPlaceholder() {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();

        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                defaults.version(), 2, defaults.contributionThresholds(),
                "cobbleraids:general/starter", defaults.specialtyTable(), defaults.bossSpecialtyTable(),
                defaults.keyFragmentTable(), defaults.keyFragmentsByBonusRolls()));
        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                defaults.version(), 2, defaults.contributionThresholds(),
                defaults.generalTable(), "cobbleraids:specialty/starter", defaults.bossSpecialtyTable(),
                defaults.keyFragmentTable(), defaults.keyFragmentsByBonusRolls()));
        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                defaults.version(), 2, defaults.contributionThresholds(),
                defaults.generalTable(), defaults.specialtyTable(), "cobbleraids:specialty/boss/charizard",
                defaults.keyFragmentTable(), defaults.keyFragmentsByBonusRolls()));
    }

    @Test
    @DisplayName("a policy from a future version is refused rather than half-understood")
    void futureVersionsAreRefused() {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();

        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                RaidRewardPolicy.CURRENT_VERSION + 1, 2, defaults.contributionThresholds(),
                defaults.generalTable(), defaults.specialtyTable(), defaults.bossSpecialtyTable(),
                defaults.keyFragmentTable(), defaults.keyFragmentsByBonusRolls()));
    }

    @Test
    @DisplayName("table ids are built from the tier and species, lowercased")
    void tableIdsAreBuiltFromTheTokens() {
        RaidRewardPolicy policy = RaidRewardPolicy.defaults();

        assertEquals("cobbleraids:general/mythical", policy.generalTableFor(RaidRarityTier.MYTHICAL));
        assertEquals("cobbleraids:specialty/powerhouse", policy.specialtyTableFor(RaidRarityTier.POWERHOUSE));
        assertEquals("cobbleraids:specialty/boss/charizard", policy.bossSpecialtyTableFor("Charizard"));
    }
}
