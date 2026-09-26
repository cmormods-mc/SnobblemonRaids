package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RaidPlayerRecords is the one piece of state in this mod that would be genuinely irrecoverable if a
 * save/load edit silently dropped or renamed a field: unlike a config, there is no sane default to
 * fall back to for a player's win count or Raid Points balance, and NBT's key-based storage means a
 * renamed field reads back as absent rather than failing loudly. This is the equivalent of
 * CobbleRaidsConfigRoundTripTest for player data, and it exists for the same reason: prove the exact
 * bytes survive a write and a read, not just that the code compiles.
 */
class RaidPlayerRecordsRoundTripTest {

    private static final ResourceLocation BLASTOISE = ResourceLocation.parse("cobbleraids:blastoise");
    private static final ResourceLocation MEGANIUM = ResourceLocation.parse("cobbleraids:meganium");

    private static RaidPlayerRecords roundTrip(Map<UUID, RaidPlayerRecord> records) {
        RaidPlayerRecords store = new RaidPlayerRecords();
        store.update(records);
        CompoundTag tag = store.save(new CompoundTag(), null);
        return RaidPlayerRecords.load(tag, null);
    }

    @Test
    @DisplayName("a fully populated record survives a save/load round trip exactly")
    void fullyPopulatedRecordRoundTrips() {
        UUID playerId = UUID.randomUUID();
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withWin(RaidRarityTier.STARTER, BLASTOISE, 40.0)
                .withWin(RaidRarityTier.LEGENDARY, MEGANIUM, 80.0)
                .withCatch()
                .withPoints(75)
                .withPurchaseOn("cobbleraids:mega_key", 86_400L, 19_000L)
                .withPurchaseOn("cobbleraids:lifetime_charm", 0L, 19_000L);

        RaidPlayerRecords reloaded = roundTrip(Map.of(playerId, record));

        RaidPlayerRecord result = reloaded.take().get(playerId);
        assertEquals(record.raidsWon(), result.raidsWon());
        assertEquals(record.winsByTier(), result.winsByTier());
        assertEquals(record.defeatsBySpecies(), result.defeatsBySpecies());
        assertEquals(record.totalContribution(), result.totalContribution(), 1.0e-9);
        assertEquals(record.bossesCaught(), result.bossesCaught());
        assertEquals(record.raidsSinceMegaStone(), result.raidsSinceMegaStone());
        assertEquals(record.raidPoints(), result.raidPoints());
        assertEquals(record.purchases(), result.purchases());
    }

    @Test
    @DisplayName("an untouched player record is the default, not absent")
    void emptyRecordRoundTrips() {
        UUID playerId = UUID.randomUUID();

        RaidPlayerRecords reloaded = roundTrip(Map.of(playerId, RaidPlayerRecord.EMPTY));

        assertEquals(RaidPlayerRecord.EMPTY, reloaded.take().get(playerId));
    }

    @Test
    @DisplayName("multiple players in one save do not bleed into each other")
    void multiplePlayersStayDistinct() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RaidPlayerRecord aliceRecord = RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.STARTER, BLASTOISE, 10.0);
        RaidPlayerRecord bobRecord = RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.MYTHICAL, MEGANIUM, 90.0).withCatch();

        Map<UUID, RaidPlayerRecord> result = roundTrip(Map.of(alice, aliceRecord, bob, bobRecord)).take();

        assertEquals(1, result.get(alice).winsIn(RaidRarityTier.STARTER));
        assertEquals(0, result.get(alice).bossesCaught());
        assertEquals(1, result.get(bob).winsIn(RaidRarityTier.MYTHICAL));
        assertEquals(1, result.get(bob).bossesCaught());
    }

    @Test
    @DisplayName("a tier name the game no longer has is dropped, not a crash")
    void unknownTierIsDroppedNotFatal() {
        UUID playerId = UUID.randomUUID();
        RaidPlayerRecords store = new RaidPlayerRecords();
        store.update(Map.of(playerId, RaidPlayerRecord.EMPTY.withWin(RaidRarityTier.STARTER, BLASTOISE, 5.0)));
        CompoundTag tag = store.save(new CompoundTag(), null);
        CompoundTag playerTag = tag.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        playerTag.getCompound("tiers").putInt("retired_tier_from_an_old_version", 7);

        RaidPlayerRecord result = RaidPlayerRecords.load(tag, null).take().get(playerId);

        assertEquals(1, result.winsIn(RaidRarityTier.STARTER), "the still-valid tier must survive");
        assertTrue(result.winsByTier().size() == 1, "the unrecognised tier must not appear at all");
    }
}
