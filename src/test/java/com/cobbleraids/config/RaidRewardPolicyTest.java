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
                defaults.generalTable(), defaults.specialtyTable(), defaults.bossSpecialtyTable());
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
    @DisplayName("the shipped thresholds are 20/35/50 for one, two and three rolls")
    void shippedThresholds() {
        List<ContributionMath.Threshold> thresholds = RaidRewardPolicy.defaults().contributionThresholds();

        assertEquals(3, thresholds.size());
        assertEquals(2, RaidRewardPolicy.defaults().standardGeneralRolls());
        assertEquals(1, ContributionMath.bonusRolls(20.0, thresholds));
        assertEquals(2, ContributionMath.bonusRolls(35.0, thresholds));
        assertEquals(3, ContributionMath.bonusRolls(50.0, thresholds));
        assertEquals(0, ContributionMath.bonusRolls(19.99, thresholds));
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
                "cobbleraids:general/starter", defaults.specialtyTable(), defaults.bossSpecialtyTable()));
        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                defaults.version(), 2, defaults.contributionThresholds(),
                defaults.generalTable(), "cobbleraids:specialty/starter", defaults.bossSpecialtyTable()));
        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                defaults.version(), 2, defaults.contributionThresholds(),
                defaults.generalTable(), defaults.specialtyTable(), "cobbleraids:specialty/boss/charizard"));
    }

    @Test
    @DisplayName("a policy from a future version is refused rather than half-understood")
    void futureVersionsAreRefused() {
        RaidRewardPolicy defaults = RaidRewardPolicy.defaults();

        assertThrows(IllegalArgumentException.class, () -> new RaidRewardPolicy(
                RaidRewardPolicy.CURRENT_VERSION + 1, 2, defaults.contributionThresholds(),
                defaults.generalTable(), defaults.specialtyTable(), defaults.bossSpecialtyTable()));
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
