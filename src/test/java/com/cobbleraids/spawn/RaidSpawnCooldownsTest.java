package com.cobbleraids.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Per-definition natural-spawn cooldowns. Previously only observable by running a server and
 * waiting, which is why the off-by-one at the boundary and the reporting of already-elapsed
 * cooldowns were never pinned.
 */
class RaidSpawnCooldownsTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.fromNamespaceAndPath("cobbleraids", "garchomp");
    private static final ResourceLocation MEWTWO = ResourceLocation.fromNamespaceAndPath("cobbleraids", "mewtwo");

    private final RaidSpawnCooldowns cooldowns = new RaidSpawnCooldowns();

    @Test
    @DisplayName("an untouched definition is always off cooldown")
    void untouchedIsOffCooldown() {
        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 0L));
        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 1_000_000L));
    }

    @Test
    @DisplayName("the cooldown ends on its exact tick, not one after")
    void endsOnTheExactTick() {
        cooldowns.start(GARCHOMP, 0L, 60);

        assertFalse(cooldowns.isOffCooldown(GARCHOMP, 60 * 20L - 1L));
        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 60 * 20L), "the boundary tick is off cooldown");
    }

    @Test
    @DisplayName("cooldowns are per definition")
    void perDefinition() {
        cooldowns.start(GARCHOMP, 0L, 60);

        assertFalse(cooldowns.isOffCooldown(GARCHOMP, 100L));
        assertTrue(cooldowns.isOffCooldown(MEWTWO, 100L));
    }

    @Test
    @DisplayName("starting again while running replaces the deadline rather than extending it")
    void restartReplaces() {
        cooldowns.start(GARCHOMP, 0L, 600);
        cooldowns.start(GARCHOMP, 100L, 10);

        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 100L + 10 * 20L));
    }

    @Test
    @DisplayName("reset clears a running cooldown and reports whether it did anything")
    void reset() {
        cooldowns.start(GARCHOMP, 0L, 600);

        assertTrue(cooldowns.reset(GARCHOMP));
        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 0L));
        assertFalse(cooldowns.reset(GARCHOMP), "nothing left to reset");
        assertFalse(cooldowns.reset(MEWTWO), "never started");
    }

    @Test
    @DisplayName("only still-running cooldowns are reported, soonest first")
    void remainingIsSortedAndFiltered() {
        cooldowns.start(GARCHOMP, 0L, 600);
        cooldowns.start(MEWTWO, 0L, 60);

        List<Map.Entry<ResourceLocation, Long>> remaining = cooldowns.remaining(0L);
        assertEquals(List.of(Map.entry(MEWTWO, 60L), Map.entry(GARCHOMP, 600L)), remaining);

        // Past the short one: an elapsed cooldown is not on cooldown, so it is not reported as zero.
        remaining = cooldowns.remaining(60 * 20L);
        assertEquals(List.of(Map.entry(GARCHOMP, 540L)), remaining);

        assertTrue(cooldowns.remaining(600 * 20L).isEmpty());
    }

    @Test
    @DisplayName("clear forgets everything, as a server restart does")
    void clear() {
        cooldowns.start(GARCHOMP, 0L, 600);
        cooldowns.clear();

        assertTrue(cooldowns.isOffCooldown(GARCHOMP, 0L));
        assertTrue(cooldowns.remaining(0L).isEmpty());
    }
}
