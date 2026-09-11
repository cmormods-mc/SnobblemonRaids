package com.cobbleraids.reward.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidRewardPolicy;
import com.cobbleraids.reward.ContributionMath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What whole parties actually walk away with.
 *
 * <p>The per-threshold arithmetic is covered elsewhere; this covers the composition of it, because
 * that is what a player experiences and what an operator gets asked about. Damage shares are run
 * through the same normalisation the victory path uses, so a change to either half shows up here.
 */
class ContributionGroupSnapshotTest {

    private static final RaidRewardPolicy POLICY = RaidRewardPolicy.defaults();

    private static RaidDefinition.Rewards policyDriven() {
        return new RaidDefinition.Rewards("cobbleraids_reward",
                Map.of("all", new RaidDefinition.RewardChoice("all", List.of(), List.of(), List.of())),
                new RaidDefinition.ContributionBonus(false, List.of(), List.of()),
                List.of());
    }

    /** Bonus rolls for each player, given raw damage numbers, in the order supplied. */
    private static List<Integer> bonusRollsFor(double... damage) {
        Map<String, Double> contribution = new LinkedHashMap<>();
        for (int index = 0; index < damage.length; index++) {
            contribution.put("p" + index, damage[index]);
        }
        Map<String, Double> percentages = ContributionMath.percentages(contribution, contribution.keySet());
        return percentages.values().stream()
                .map(share -> RewardPlanResolver.bonusRollsFor(policyDriven(), null, POLICY, share))
                .toList();
    }

    /** Total selections each player receives: two standard general, one specialty, plus bonuses. */
    private static List<Integer> selectionsFor(double... damage) {
        return bonusRollsFor(damage).stream().map(bonus -> bonus + 3).toList();
    }

    @Test
    @DisplayName("every threshold boundary lands on the side it should")
    void boundaries() {
        double[] shares = {0.0, 19.99, 20.0, 34.99, 35.0, 49.99, 50.0, 100.0};
        int[] expected = {0, 0, 1, 1, 2, 2, 3, 3};

        for (int index = 0; index < shares.length; index++) {
            assertEquals(expected[index],
                    RewardPlanResolver.bonusRollsFor(policyDriven(), null, POLICY, shares[index]),
                    "share " + shares[index] + "%");
        }
    }

    @Test
    @DisplayName("a solo victor takes the maximum, at six selections")
    void solo() {
        assertEquals(List.of(6), selectionsFor(1000.0));
    }

    @Test
    @DisplayName("an even pair each clear the top threshold")
    void evenPair() {
        assertEquals(List.of(3, 3), bonusRollsFor(500.0, 500.0));
    }

    @Test
    @DisplayName("an even four-way party each land on one bonus roll")
    void evenFourWay() {
        // 25% each: past 20, short of 35. Four selections apiece.
        assertEquals(List.of(1, 1, 1, 1), bonusRollsFor(250.0, 250.0, 250.0, 250.0));
        assertEquals(List.of(4, 4, 4, 4), selectionsFor(250.0, 250.0, 250.0, 250.0));
    }

    @Test
    @DisplayName("an uneven trio is paid by share, not by turning up")
    void unevenTrio() {
        assertEquals(List.of(2, 2, 1), bonusRollsFor(40.0, 35.0, 25.0));
    }

    @Test
    @DisplayName("a carry and three tag-alongs are paid very differently")
    void carryAndTagAlongs() {
        assertEquals(List.of(3, 1, 0, 0), bonusRollsFor(60.0, 20.0, 10.0, 10.0));
        assertEquals(List.of(6, 4, 3, 3), selectionsFor(60.0, 20.0, 10.0, 10.0));
    }

    @Test
    @DisplayName("a player who did nothing still gets the three standard selections")
    void zeroDamageStillClaims() {
        // Eligibility is decided elsewhere; anyone who reaches this point has earned the standard
        // bundle, and contribution only ever adds to it.
        assertEquals(List.of(3, 3), selectionsFor(100.0, 0.0).subList(1, 2).isEmpty()
                ? List.of(3, 3) : List.of(selectionsFor(100.0, 0.0).get(1), 3));
        assertEquals(0, bonusRollsFor(100.0, 0.0).get(1));
    }

    @Test
    @DisplayName("rounding cannot promote a share that is just under a threshold")
    void roundingCannotPromote() {
        // 19.99% displays as 20.0% at one decimal place. What the player sees must not be what
        // decides the payout.
        assertEquals(0, RewardPlanResolver.bonusRollsFor(policyDriven(), null, POLICY, 19.99));
        assertEquals(0, RewardPlanResolver.bonusRollsFor(policyDriven(), null, POLICY, 19.999));
    }

    @Test
    @DisplayName("a third of a raid clears the 20% threshold despite floating-point drift")
    void thirdsClearTheLowestThreshold() {
        assertEquals(List.of(1, 1, 1), bonusRollsFor(1.0, 1.0, 1.0));
    }
}
