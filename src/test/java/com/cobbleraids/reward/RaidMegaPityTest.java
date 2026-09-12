package com.cobbleraids.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.CobbleRaidsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bad-luck protection on Mega Stones, and the rule that decides when a stone has been found.
 *
 * <p>The counter is what lets the drop rate be 10% rather than 15%: a floor under the tail averages
 * the same four hours over a far tighter distribution, and caps a drought at the twelfth
 * mega-capable raid instead of leaving it unbounded.
 */
class RaidMegaPityTest {

    private static final CobbleRaidsConfig.MegaPity SHIPPED = CobbleRaidsConfig.MegaPity.defaults();
    private static final CobbleRaidsConfig.MegaPity OFF = new CobbleRaidsConfig.MegaPity(false, 12);

    @Test
    @DisplayName("the guarantee fires on the twelfth capable raid without a stone, not before")
    void firesAtTheThreshold() {
        for (int since = 0; since < 12; since++) {
            assertFalse(RaidMegaPity.guaranteed(since, SHIPPED), "fired early at " + since);
        }
        assertTrue(RaidMegaPity.guaranteed(12, SHIPPED));
        // A counter that somehow ran past the threshold still pays, rather than only paying on the
        // exact number: an off-by-one there would strand a player permanently.
        assertTrue(RaidMegaPity.guaranteed(13, SHIPPED));
        assertTrue(RaidMegaPity.guaranteed(500, SHIPPED));
    }

    @Test
    @DisplayName("switched off, nothing is ever guaranteed")
    void disabledNeverFires() {
        assertFalse(RaidMegaPity.guaranteed(0, OFF));
        assertFalse(RaidMegaPity.guaranteed(999, OFF));
        assertFalse(RaidMegaPity.guaranteed(999, null));
    }

    @Test
    @DisplayName("a Mega Stone is recognised, and resets the counter")
    void stonesAreRecognised() {
        assertTrue(RaidMegaPity.isMegaStone("mega_showdown:charizardite_x"));
        assertTrue(RaidMegaPity.isMegaStone("mega_showdown:charizardite_y"));
        assertTrue(RaidMegaPity.isMegaStone("mega_showdown:mewtwonite_x"));
        assertTrue(RaidMegaPity.isMegaStone("mega_showdown:venusaurite"));
        assertTrue(RaidMegaPity.isMegaStone("mega_showdown:diancite"));
    }

    @Test
    @DisplayName("a Tera Shard shares the namespace but is not a stone")
    void teraShardsAreNotStones() {
        // The one thing that would quietly break this rule: shards come from the same mod, and
        // counting one as a stone would reset a player's protection for the wrong reward.
        assertFalse(RaidMegaPity.isMegaStone("mega_showdown:fire_tera_shard"));
        assertFalse(RaidMegaPity.isMegaStone("mega_showdown:stellar_tera_shard"));
        assertFalse(RaidMegaPity.isMegaStone("mega_showdown:dragon_tera_shard"));
    }

    @Test
    @DisplayName("nothing from another mod counts")
    void otherNamespacesAreNotStones() {
        assertFalse(RaidMegaPity.isMegaStone("cobblemon:rare_candy"));
        assertFalse(RaidMegaPity.isMegaStone("cobblemoncharms:shiny_charm"));
        assertFalse(RaidMegaPity.isMegaStone("minecraft:diamond"));
        assertFalse(RaidMegaPity.isMegaStone("charizardite_x"));
        assertFalse(RaidMegaPity.isMegaStone(null));
        assertFalse(RaidMegaPity.isMegaStone(""));
    }

    @Test
    @DisplayName("the shipped threshold bounds a drought to roughly its stated length")
    void thresholdMatchesTheDesign() {
        // 3 raids an hour, 3x bias making 1.80 of them mega-capable: twelve capable raids is about
        // six and three quarter hours. Pinned so raising the threshold cannot quietly become a
        // twenty-hour guarantee.
        double capablePerHour = 1.80;
        double worstCaseHours = SHIPPED.threshold() / capablePerHour;

        assertEquals(12, SHIPPED.threshold());
        assertTrue(worstCaseHours < 8.0,
                "worst case is " + Math.round(worstCaseHours) + " hours, which is not 'not too rare'");
    }
}
