package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record is the substrate a catch mechanic is eventually chosen from, so what matters is that
 * it accumulates faithfully and is immutable. A points economy, a reputation system and a
 * per-species progression all read the same numbers; getting them wrong would be invisible until a
 * mechanic shipped on top.
 */
class RaidPlayerRecordTest {

    private static final ResourceLocation BLASTOISE = ResourceLocation.parse("cobbleraids:blastoise");
    private static final ResourceLocation MEGANIUM = ResourceLocation.parse("cobbleraids:meganium");

    @Test
    @DisplayName("a new player has beaten nothing")
    void emptyIsEmpty() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;

        assertEquals(0, record.raidsWon());
        assertEquals(0, record.bossesCaught());
        assertEquals(0, record.defeatsOf(BLASTOISE));
        assertEquals(0, record.winsIn(RaidRarityTier.LEGENDARY));
        assertEquals(0.0, record.averageContribution(), 0.0, "must not divide by zero wins");
    }

    @Test
    @DisplayName("wins accumulate by tier and by species")
    void winsAccumulate() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 50.0)
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 100.0)
                .withWin(RaidRarityTier.LEGENDARY, MEGANIUM, 30.0);

        assertEquals(3, record.raidsWon());
        assertEquals(2, record.winsIn(RaidRarityTier.STARTER));
        assertEquals(1, record.winsIn(RaidRarityTier.LEGENDARY));
        assertEquals(0, record.winsIn(RaidRarityTier.MYTHICAL));
        assertEquals(2, record.defeatsOf(BLASTOISE), "per-species count drives a progression mechanic");
        assertEquals(1, record.defeatsOf(MEGANIUM));
        assertEquals(60.0, record.averageContribution(), 1.0e-9);
    }

    @Test
    @DisplayName("catching is counted separately from winning")
    void catchesAreSeparate() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 100.0)
                .withCatch();

        assertEquals(1, record.raidsWon());
        assertEquals(1, record.bossesCaught());
        assertEquals(1, record.defeatsOf(BLASTOISE), "a catch must not also count as a win");
    }

    @Test
    @DisplayName("each update returns a new record and leaves the old one alone")
    void updatesAreImmutable() {
        RaidPlayerRecord before = RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.STARTER, BLASTOISE, 40.0);

        RaidPlayerRecord after = before.withWin(RaidRarityTier.STARTER, BLASTOISE, 60.0);

        assertEquals(1, before.raidsWon(), "the earlier record must not have moved");
        assertEquals(2, after.raidsWon());
        assertEquals(40.0, before.averageContribution(), 1.0e-9);
        assertEquals(50.0, after.averageContribution(), 1.0e-9);
    }

    @Test
    @DisplayName("the stored maps cannot be mutated by a caller")
    void mapsAreUnmodifiable() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.STARTER, BLASTOISE, 10.0);

        assertThrows(UnsupportedOperationException.class,
                () -> record.winsByTier().put(RaidRarityTier.MYTHICAL, 99));
        assertThrows(UnsupportedOperationException.class,
                () -> record.defeatsBySpecies().put(MEGANIUM, 99));
    }

    @Test
    @DisplayName("history survives a long campaign without drifting")
    void manyWinsStayConsistent() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;
        for (int i = 0; i < 500; i++) {
            record = record.withWin(RaidRarityTier.POWERHOUSE, BLASTOISE, 25.0);
        }

        assertEquals(500, record.raidsWon());
        assertEquals(500, record.defeatsOf(BLASTOISE));
        assertEquals(500, record.winsIn(RaidRarityTier.POWERHOUSE));
        assertEquals(25.0, record.averageContribution(), 1.0e-9);
        assertTrue(record.winsByTier().size() == 1, "only tiers actually played should appear");
    }
}
