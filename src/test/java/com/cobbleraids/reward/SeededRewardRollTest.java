package com.cobbleraids.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seed is only worth having if it actually reproduces a claim. These cover the part of that
 * which needs no server: the same claim seed must draw the same sequence, and two different claims
 * must not be handed the same reward.
 */
class SeededRewardRollTest {

    private static List<RaidDefinition.RewardItem> pool() {
        List<RaidDefinition.RewardItem> pool = new ArrayList<>();
        pool.add(new RaidDefinition.RewardItem(ResourceLocation.parse("cobblemon:rare_candy"), 1, 1.0, 50));
        pool.add(new RaidDefinition.RewardItem(ResourceLocation.parse("cobblemon:ultra_ball"), 3, 1.0, 35));
        pool.add(new RaidDefinition.RewardItem(ResourceLocation.parse("cobblemon:exp_candy_l"), 1, 1.0, 15));
        return pool;
    }

    private static List<String> draw(long seed, int count) {
        Random random = new Random(seed);
        List<String> drawn = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            drawn.add(RaidRewardGrantEngine.weighted(pool(), random).item().toString());
        }
        return drawn;
    }

    @Test
    @DisplayName("the same claim seed reproduces the same sequence of picks")
    void sameSeedSameSequence() {
        assertEquals(draw(20260911L, 8), draw(20260911L, 8));
    }

    @Test
    @DisplayName("different claims do not all get the same reward")
    void differentSeedsDiverge() {
        // Two claims from the same raid must not be handed identical bundles just because the
        // reward is now reproducible.
        assertTrue(draw(1L, 12).equals(draw(2L, 12)) == false,
                "two different seeds produced identical draws, which means the seed is being ignored");
    }

    @Test
    @DisplayName("one claim's successive selections differ from each other")
    void successiveSelectionsDiffer() {
        // The trap this guards: seeding every roll in a bundle with the claim seed itself, which is
        // perfectly reproducible and gives the player the same item three times over.
        List<String> drawn = draw(4242L, 20);

        assertTrue(drawn.stream().distinct().count() > 1,
                "every selection in the bundle was identical: " + drawn.get(0));
    }

    @Test
    @DisplayName("picks respect the pool weights over a long run")
    void weightsStillApply() {
        List<String> drawn = draw(99L, 4000);
        long candy = drawn.stream().filter(id -> id.endsWith("rare_candy")).count();

        // 50/100 weight; a wide band, since this is asserting the weighting is applied at all
        // rather than pinning the exact behaviour of java.util.Random.
        assertTrue(candy > 1600 && candy < 2400, "rare_candy drawn " + candy + " times in 4000");
    }
}
