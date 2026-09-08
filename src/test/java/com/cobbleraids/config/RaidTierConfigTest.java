package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Config validation runs while the server is loading a datapack, so a value that slips through
 * becomes a wrong spawn rate nobody can see rather than an error somebody can read.
 */
class RaidTierConfigTest {

    @Nested
    @DisplayName("tier_weights")
    class Weights {

        @Test
        @DisplayName("defaults favour common raids")
        void defaultsAreTheDocumentedMix() {
            RaidTierWeights weights = RaidTierWeights.defaults();

            assertEquals(70, weights.starter());
            assertEquals(20, weights.powerhouse());
            assertEquals(8, weights.legendary());
            assertEquals(2, weights.mythical());
        }

        @Test
        @DisplayName("every tier maps to its own weight")
        void weightLookupCoversEveryTier() {
            RaidTierWeights weights = new RaidTierWeights(1, 2, 3, 4);

            assertEquals(1, weights.weightFor(RaidRarityTier.STARTER));
            assertEquals(2, weights.weightFor(RaidRarityTier.POWERHOUSE));
            assertEquals(3, weights.weightFor(RaidRarityTier.LEGENDARY));
            assertEquals(4, weights.weightFor(RaidRarityTier.MYTHICAL));
        }

        @Test
        @DisplayName("a single tier may carry all the weight")
        void oneTierIsEnough() {
            assertEquals(5, new RaidTierWeights(0, 0, 0, 5).weightFor(RaidRarityTier.MYTHICAL));
        }

        @Test
        @DisplayName("all-zero weights are rejected rather than silently disabling every raid")
        void allZeroIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new RaidTierWeights(0, 0, 0, 0));
        }

        @Test
        @DisplayName("a negative weight is rejected")
        void negativeIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new RaidTierWeights(-1, 20, 8, 2));
        }

        @Test
        @DisplayName("weights are capped, so the cumulative walk cannot overflow")
        void oversizedWeightIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new RaidTierWeights(1_000_001, 0, 0, 1));
        }
    }

    @Nested
    @DisplayName("tier_spawn_chance")
    class SpawnChance {

        @Test
        @DisplayName("defaults leave an existing server.json behaving exactly as before")
        void defaultsAreFullRate() {
            RaidTierSpawnChance chances = RaidTierSpawnChance.defaults();

            for (RaidRarityTier tier : RaidRarityTier.values()) {
                assertEquals(1.0, chances.chanceFor(tier), 0.0, tier.name());
            }
        }

        @Test
        @DisplayName("every tier maps to its own chance")
        void chanceLookupCoversEveryTier() {
            RaidTierSpawnChance chances = new RaidTierSpawnChance(0.1, 0.2, 0.3, 0.4);

            assertEquals(0.1, chances.chanceFor(RaidRarityTier.STARTER), 0.0);
            assertEquals(0.2, chances.chanceFor(RaidRarityTier.POWERHOUSE), 0.0);
            assertEquals(0.3, chances.chanceFor(RaidRarityTier.LEGENDARY), 0.0);
            assertEquals(0.4, chances.chanceFor(RaidRarityTier.MYTHICAL), 0.0);
        }

        @Test
        @DisplayName("the live server's own settings are accepted")
        void acceptsTheLiveConfiguration() {
            RaidTierSpawnChance chances = new RaidTierSpawnChance(0.20, 0.50, 1.0, 1.0);

            assertEquals(0.20, chances.chanceFor(RaidRarityTier.STARTER), 0.0);
            assertEquals(1.0, chances.chanceFor(RaidRarityTier.MYTHICAL), 0.0);
        }

        @Test
        @DisplayName("all zero is allowed: it is a coherent way to say no wild raids")
        void allZeroIsAllowedButWarned() {
            // Deliberately not fatal -- admin spawns still work -- so this must construct.
            RaidTierSpawnChance chances = new RaidTierSpawnChance(0.0, 0.0, 0.0, 0.0);

            assertEquals(0.0, chances.chanceFor(RaidRarityTier.STARTER), 0.0);
        }

        @Test
        @DisplayName("a chance above 1 is rejected rather than read as a percentage")
        void aboveOneIsRejected() {
            // 100 meaning "100%" is the obvious mistake to make in a JSON file of 0..1 doubles.
            assertThrows(IllegalArgumentException.class,
                    () -> new RaidTierSpawnChance(100.0, 1.0, 1.0, 1.0));
        }

        @Test
        @DisplayName("a negative chance is rejected")
        void negativeIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RaidTierSpawnChance(-0.1, 1.0, 1.0, 1.0));
        }

        @Test
        @DisplayName("NaN is rejected, since every comparison against it silently fails")
        void nanIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RaidTierSpawnChance(Double.NaN, 1.0, 1.0, 1.0));
        }
    }
}
