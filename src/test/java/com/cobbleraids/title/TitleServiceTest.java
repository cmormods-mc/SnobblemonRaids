package com.cobbleraids.title;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TitleService#qualifies}, the one rule that decides whether a raid win or a catch
 * crosses a title's threshold. Driven directly against a plain {@link RaidPlayerRecord}, with no
 * {@code MinecraftServer} needed -- {@link TitleService#checkUnlocks}'s own job (looking a title up,
 * persisting the unlock, messaging the player) is thin enough to cover live instead.
 */
class TitleServiceTest {

    private static final ResourceLocation BLASTOISE = ResourceLocation.parse("cobbleraids:blastoise");

    @Test
    @DisplayName("RAIDS_WON qualifies once total wins reach the threshold, not before")
    void raidsWonThreshold() {
        TitleDefinition title = new TitleDefinition("t", "T", "WHITE", TitleUnlockType.RAIDS_WON, null, 3);
        RaidPlayerRecord below = RaidPlayerRecord.EMPTY
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 1.0)
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 1.0);
        RaidPlayerRecord atThreshold = below.withWin(RaidRarityTier.STARTER, BLASTOISE, 1.0);

        assertFalse(TitleService.qualifies(below, title));
        assertTrue(TitleService.qualifies(atThreshold, title));
    }

    @Test
    @DisplayName("TIER_WINS only counts wins of its own tier, and needs a tier to check at all")
    void tierWinsThresholdIsTierSpecific() {
        TitleDefinition legendaryTitle = new TitleDefinition("t", "T", "WHITE",
                TitleUnlockType.TIER_WINS, RaidRarityTier.LEGENDARY, 2);
        TitleDefinition brokenTitle = new TitleDefinition("broken", "Broken", "WHITE",
                TitleUnlockType.TIER_WINS, null, 2);
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 1.0)
                .withWin(RaidRarityTier.LEGENDARY, BLASTOISE, 1.0)
                .withWin(RaidRarityTier.LEGENDARY, BLASTOISE, 1.0);

        assertTrue(TitleService.qualifies(record, legendaryTitle), "two legendary wins must satisfy a threshold of two");
        assertFalse(TitleService.qualifies(record, brokenTitle), "a null tier must never quietly match");
    }

    @Test
    @DisplayName("BOSSES_CAUGHT reads the catch counter, not the win counter")
    void bossesCaughtThreshold() {
        TitleDefinition title = new TitleDefinition("t", "T", "WHITE", TitleUnlockType.BOSSES_CAUGHT, null, 1);
        RaidPlayerRecord wonNotCaught = RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.STARTER, BLASTOISE, 1.0);
        RaidPlayerRecord caught = wonNotCaught.withCatch();

        assertFalse(TitleService.qualifies(wonNotCaught, title));
        assertTrue(TitleService.qualifies(caught, title));
    }
}
