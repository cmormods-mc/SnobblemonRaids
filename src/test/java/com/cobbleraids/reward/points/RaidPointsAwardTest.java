package com.cobbleraids.reward.points;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.RaidRarityTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a raid pays in Raid Points, and how a balance behaves.
 *
 * <p>The store itself needs a server to persist through, so what is tested here is the part that
 * decides: the per-tier award and the arithmetic of a balance, including the two cases a shop will
 * lean on hardest -- a spend that must be refused, and a balance that must never go negative.
 */
class RaidPointsAwardTest {

    private static final CobbleRaidsConfig.RaidPoints SHIPPED = CobbleRaidsConfig.RaidPoints.defaults();

    @Test
    @DisplayName("each tier pays what it was specified to pay")
    void shippedAwards() {
        assertEquals(25, SHIPPED.pointsFor(RaidRarityTier.STARTER));
        assertEquals(50, SHIPPED.pointsFor(RaidRarityTier.POWERHOUSE));
        assertEquals(75, SHIPPED.pointsFor(RaidRarityTier.LEGENDARY));
        assertEquals(100, SHIPPED.pointsFor(RaidRarityTier.MYTHICAL));
        assertTrue(SHIPPED.enabled());
        assertFalse(SHIPPED.isNoOp());
    }

    @Test
    @DisplayName("a rarer raid never pays less than a commoner one")
    void awardsRiseWithTier() {
        int previous = 0;
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            int award = SHIPPED.pointsFor(tier);
            assertTrue(award > previous, tier + " pays " + award + ", not more than the tier below");
            previous = award;
        }
    }

    @Test
    @DisplayName("all-zero reports itself as a no-op so the claim path can skip it")
    void zeroIsANoOp() {
        assertTrue(new CobbleRaidsConfig.RaidPoints(true, 0, 0, 0, 0).isNoOp());
        assertFalse(new CobbleRaidsConfig.RaidPoints(true, 0, 0, 0, 1).isNoOp());
    }

    @Test
    @DisplayName("negative or absurd awards are refused at construction")
    void awardsAreBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.RaidPoints(true, -1, 50, 75, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.RaidPoints(true, 25, 50, 75, 100_001));
    }

    @Test
    @DisplayName("a balance accumulates and spends")
    void balanceArithmetic() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;

        assertEquals(0, record.raidPoints());
        record = record.withPoints(25).withPoints(75);
        assertEquals(100, record.raidPoints());
        record = record.withPoints(-40);
        assertEquals(60, record.raidPoints());
    }

    @Test
    @DisplayName("a balance never goes negative, whatever it is asked to subtract")
    void balanceFloorsAtZero() {
        // A debt is not a state this mod can resolve, and a shop refusing an unaffordable purchase
        // is the layer that stops one arising -- this is the backstop under it.
        assertEquals(0, RaidPlayerRecord.EMPTY.withPoints(10).withPoints(-999).raidPoints());
        assertEquals(0, RaidPlayerRecord.EMPTY.withPoints(-1).raidPoints());
    }

    @Test
    @DisplayName("a balance cannot be overflowed into a negative by a huge award")
    void balanceCannotOverflow() {
        RaidPlayerRecord rich = RaidPlayerRecord.EMPTY.withPoints(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, rich.withPoints(1000).raidPoints());
    }

    @Test
    @DisplayName("points survive the record's other updates")
    void pointsSurviveOtherWrites() {
        // Winning a raid and catching a boss both rebuild the record; a balance dropped by either
        // would be a silent theft nobody would trace back.
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY.withPoints(150);

        assertEquals(150, record.withCatch().raidPoints());
        assertEquals(150, record.withWin(RaidRarityTier.LEGENDARY,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobbleraids", "mewtwo"),
                50.0).raidPoints());
    }
}
