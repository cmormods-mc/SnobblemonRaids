package com.cobbleraids.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidTierSpawnChance;
import com.cobbleraids.config.RaidTierWeights;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The selector decides how often every wild raid happens, and getting it wrong is invisible: the
 * server keeps spawning raids, just at the wrong rate and the wrong mix, and nobody can tell by
 * looking. These tests pin the behaviour that has actually been misunderstood in operation.
 */
class RaidTierSelectorTest {
    private static final double EPSILON = 1.0e-9;

    /** A definition stand-in: the selector is generic and never touches Minecraft types. */
    private record Entry(String id, RaidRarityTier tier, int weight) {}

    private static final Function<Entry, RaidRarityTier> TIER_OF = Entry::tier;
    private static final ToIntFunction<Entry> WEIGHT_OF = Entry::weight;

    private static Entry entry(String id, RaidRarityTier tier) {
        return new Entry(id, tier, 1);
    }

    private static Map<RaidRarityTier, Integer> counts(RaidRarityTier... tiers) {
        EnumMap<RaidRarityTier, Integer> counts = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) counts.put(tier, 0);
        for (RaidRarityTier tier : tiers) counts.merge(tier, 1, Integer::sum);
        return counts;
    }

    @Test
    @DisplayName("tier weights are a mix selector, not a rate: emptying a tier promotes the others")
    void weightsRenormaliseAcrossEligibleTiersOnly() {
        // The lesson that cost a live server a mis-tune. With only legendary definitions eligible,
        // a weight of 8 against starter's 70 still produces legendary raids 100% of the time --
        // lowering a weight converts spawns into other tiers, it never removes them.
        Map<RaidRarityTier, Double> odds = RaidTierSelector.normalizedPercentages(
                counts(RaidRarityTier.LEGENDARY),
                new RaidTierWeights(70, 20, 8, 2),
                RaidTierSpawnChance.defaults());

        assertEquals(100.0, odds.get(RaidRarityTier.LEGENDARY), EPSILON);
        assertEquals(0.0, odds.get(RaidRarityTier.STARTER), EPSILON);
        assertEquals(0.0, RaidTierSelector.noSpawnPercentage(odds), EPSILON);
    }

    @Test
    @DisplayName("tier_spawn_chance is the rate dial: lowering one tier leaves the others untouched")
    void spawnChanceIsIndependentPerTier() {
        // The whole point of tier_spawn_chance existing. A failed roll is "no raid this attempt",
        // never a re-roll into another tier, so turning starter down must not inflate legendary.
        Map<RaidRarityTier, Integer> pool = counts(
                RaidRarityTier.STARTER, RaidRarityTier.POWERHOUSE,
                RaidRarityTier.LEGENDARY, RaidRarityTier.MYTHICAL);
        RaidTierWeights weights = new RaidTierWeights(70, 20, 8, 2);

        Map<RaidRarityTier, Double> before = RaidTierSelector.normalizedPercentages(
                pool, weights, RaidTierSpawnChance.defaults());
        Map<RaidRarityTier, Double> after = RaidTierSelector.normalizedPercentages(
                pool, weights, new RaidTierSpawnChance(0.20, 0.50, 1.0, 1.0));

        assertEquals(before.get(RaidRarityTier.LEGENDARY), after.get(RaidRarityTier.LEGENDARY), EPSILON);
        assertEquals(before.get(RaidRarityTier.MYTHICAL), after.get(RaidRarityTier.MYTHICAL), EPSILON);
        assertEquals(70.0 * 0.20, after.get(RaidRarityTier.STARTER), EPSILON);
        assertEquals(20.0 * 0.50, after.get(RaidRarityTier.POWERHOUSE), EPSILON);
    }

    @Test
    @DisplayName("reported odds plus the no-spawn share account for every attempt")
    void oddsAndNoSpawnShareSumToOneHundred() {
        Map<RaidRarityTier, Double> odds = RaidTierSelector.normalizedPercentages(
                counts(RaidRarityTier.STARTER, RaidRarityTier.POWERHOUSE,
                        RaidRarityTier.LEGENDARY, RaidRarityTier.MYTHICAL),
                new RaidTierWeights(70, 20, 8, 2),
                new RaidTierSpawnChance(0.20, 0.50, 1.0, 1.0));

        // The live server's own settings, worked through: 70*0.20 + 20*0.50 + 8 + 2 = 34% of
        // attempts produce a raid at all. Two thirds producing nothing is by design, but it is a
        // surprising number to arrive at from the config file, which is why it is pinned here.
        double spawned = odds.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(34.0, spawned, EPSILON);
        assertEquals(100.0, spawned + RaidTierSelector.noSpawnPercentage(odds), EPSILON);
        assertEquals(66.0, RaidTierSelector.noSpawnPercentage(odds), EPSILON);
    }

    @Test
    @DisplayName("every tier at chance 0 reports a 100% no-spawn rate rather than a silent zero")
    void allChancesZeroMeansNothingEverSpawns() {
        Map<RaidRarityTier, Double> odds = RaidTierSelector.normalizedPercentages(
                counts(RaidRarityTier.STARTER, RaidRarityTier.LEGENDARY),
                RaidTierWeights.defaults(),
                new RaidTierSpawnChance(0.0, 0.0, 0.0, 0.0));

        assertEquals(100.0, RaidTierSelector.noSpawnPercentage(odds), EPSILON);
    }

    @Test
    @DisplayName("no eligible definitions reports 100% no-spawn, not a division by zero")
    void emptyPoolIsFinite() {
        Map<RaidRarityTier, Double> odds = RaidTierSelector.normalizedPercentages(
                counts(), RaidTierWeights.defaults(), RaidTierSpawnChance.defaults());

        for (double value : odds.values()) assertEquals(0.0, value, EPSILON);
        assertEquals(100.0, RaidTierSelector.noSpawnPercentage(odds), EPSILON);
    }

    @Test
    @DisplayName("selection matches the configured mix over many rolls")
    void selectionFollowsTierWeights() {
        List<Entry> pool = List.of(
                entry("s", RaidRarityTier.STARTER),
                entry("p", RaidRarityTier.POWERHOUSE),
                entry("l", RaidRarityTier.LEGENDARY),
                entry("m", RaidRarityTier.MYTHICAL));
        RaidTierWeights weights = new RaidTierWeights(70, 20, 8, 2);
        Random random = new Random(20260908L); // Fixed seed: this assertion must never flake in CI.

        EnumMap<RaidRarityTier, Integer> observed = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) observed.put(tier, 0);
        int rolls = 200_000;
        for (int i = 0; i < rolls; i++) {
            Entry selected = RaidTierSelector.select(pool, TIER_OF, WEIGHT_OF, weights, random);
            observed.merge(selected.tier(), 1, Integer::sum);
        }

        assertEquals(70.0, observed.get(RaidRarityTier.STARTER) * 100.0 / rolls, 1.0);
        assertEquals(20.0, observed.get(RaidRarityTier.POWERHOUSE) * 100.0 / rolls, 1.0);
        assertEquals(8.0, observed.get(RaidRarityTier.LEGENDARY) * 100.0 / rolls, 1.0);
        assertEquals(2.0, observed.get(RaidRarityTier.MYTHICAL) * 100.0 / rolls, 1.0);
    }

    @Test
    @DisplayName("a tier weighted 0 is never selected even while its definitions are eligible")
    void zeroWeightTierIsNeverSelected() {
        List<Entry> pool = List.of(
                entry("s", RaidRarityTier.STARTER), entry("m", RaidRarityTier.MYTHICAL));
        RaidTierWeights weights = new RaidTierWeights(1, 0, 0, 0);
        Random random = new Random(7L);

        for (int i = 0; i < 5_000; i++) {
            assertEquals(RaidRarityTier.STARTER,
                    RaidTierSelector.select(pool, TIER_OF, WEIGHT_OF, weights, random).tier());
        }
    }

    @Test
    @DisplayName("within a tier, per-definition weights decide the species")
    void withinTierWeightsDecideTheSpecies() {
        List<Entry> pool = List.of(
                new Entry("common", RaidRarityTier.STARTER, 9),
                new Entry("rare", RaidRarityTier.STARTER, 1));
        Random random = new Random(99L);

        int rare = 0;
        int rolls = 100_000;
        for (int i = 0; i < rolls; i++) {
            Entry selected = RaidTierSelector.select(
                    pool, TIER_OF, WEIGHT_OF, RaidTierWeights.defaults(), random);
            if ("rare".equals(selected.id())) rare++;
        }

        assertEquals(10.0, rare * 100.0 / rolls, 0.5);
    }

    @Test
    @DisplayName("every definition in the selected tier is reachable")
    void everyDefinitionIsReachable() {
        // A boundary slip in the cumulative-weight walk would silently make one definition
        // unspawnable -- the kind of bug a player reports months later as "we never see X".
        List<Entry> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) pool.add(entry("d" + i, RaidRarityTier.STARTER));
        Random random = new Random(4242L);

        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            String id = RaidTierSelector.select(
                    pool, TIER_OF, WEIGHT_OF, RaidTierWeights.defaults(), random).id();
            if (!seen.contains(id)) seen.add(id);
        }

        assertEquals(pool.size(), seen.size(), "unreachable definition(s), saw only " + seen);
    }

    @Test
    @DisplayName("an empty pool selects nothing instead of throwing into the spawn tick")
    void emptyPoolSelectsNothing() {
        assertNull(RaidTierSelector.select(
                List.of(), TIER_OF, WEIGHT_OF, RaidTierWeights.defaults(), new Random(1L)));
    }

    @Test
    @DisplayName("selects nothing when every eligible tier is weighted 0")
    void noWeightOnAnyEligibleTierSelectsNothing() {
        List<Entry> pool = List.of(entry("m", RaidRarityTier.MYTHICAL));
        assertNull(RaidTierSelector.select(
                pool, TIER_OF, WEIGHT_OF, new RaidTierWeights(1, 0, 0, 0), new Random(1L)));
    }

    @Test
    @DisplayName("a non-positive definition weight is rejected rather than skewing the draw")
    void nonPositiveDefinitionWeightIsRejected() {
        List<Entry> pool = List.of(new Entry("broken", RaidRarityTier.STARTER, 0));
        assertThrows(IllegalArgumentException.class, () -> RaidTierSelector.select(
                pool, TIER_OF, WEIGHT_OF, RaidTierWeights.defaults(), new Random(1L)));
    }

    @Test
    @DisplayName("counts report every tier, including the empty ones")
    void countsCoverEveryTier() {
        Map<RaidRarityTier, Integer> counted = RaidTierSelector.counts(
                List.of(entry("a", RaidRarityTier.STARTER), entry("b", RaidRarityTier.STARTER)),
                TIER_OF);

        assertEquals(RaidRarityTier.values().length, counted.size());
        assertEquals(2, counted.get(RaidRarityTier.STARTER));
        assertEquals(0, counted.get(RaidRarityTier.MYTHICAL));
    }

    @Test
    @DisplayName("selection never returns null while some eligible tier carries weight")
    void selectionAlwaysProducesAResultWhenItShould() {
        List<Entry> pool = List.of(
                entry("s", RaidRarityTier.STARTER), entry("l", RaidRarityTier.LEGENDARY));
        Random random = new Random(11L);

        for (int i = 0; i < 10_000; i++) {
            assertNotNull(RaidTierSelector.select(
                    pool, TIER_OF, WEIGHT_OF, RaidTierWeights.defaults(), random));
        }
    }
}
