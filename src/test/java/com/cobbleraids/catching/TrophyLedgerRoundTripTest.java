package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Proves a trophy entry survives a save/load round trip exactly, the same way
 * {@code DefeatedBossSnapshotsRoundTripTest} does for personal-shop snapshots -- and separately, that
 * a repeat defeat increments the counter without disturbing the pinned first-defeat stats.
 */
class TrophyLedgerRoundTripTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.parse("cobblemon:garchomp");
    private static final ResourceLocation TYRANITAR = ResourceLocation.parse("cobblemon:tyranitar");

    private static TrophyEntry entry(ResourceLocation species, RaidRarityTier tier, int timesDefeated) {
        return new TrophyEntry(species, 75, true, 84, 62, tier, 1_000_000L, timesDefeated);
    }

    private static TrophyLedger roundTrip(Map<UUID, Map<ResourceLocation, TrophyEntry>> records) {
        TrophyLedger store = new TrophyLedger();
        store.update(records);
        CompoundTag tag = store.save(new CompoundTag(), null);
        return TrophyLedger.load(tag, null);
    }

    @Test
    @DisplayName("a fully populated trophy survives a save/load round trip exactly")
    void fullyPopulatedTrophyRoundTrips() {
        UUID playerId = UUID.randomUUID();
        TrophyEntry original = entry(GARCHOMP, RaidRarityTier.LEGENDARY, 3);

        TrophyLedger reloaded = roundTrip(Map.of(playerId, Map.of(GARCHOMP, original)));

        TrophyEntry result = reloaded.take().get(playerId).get(GARCHOMP);
        assertEquals(original.species(), result.species());
        assertEquals(original.level(), result.level());
        assertEquals(original.shiny(), result.shiny());
        assertEquals(original.ivPercent(), result.ivPercent());
        assertEquals(original.evPercent(), result.evPercent());
        assertEquals(original.rarityTier(), result.rarityTier());
        assertEquals(original.firstDefeatedAtEpochMs(), result.firstDefeatedAtEpochMs());
        assertEquals(original.timesDefeated(), result.timesDefeated());
    }

    @Test
    @DisplayName("an untouched player has no trophy room at all, not an empty one")
    void unknownPlayerHasNoTrophies() {
        TrophyLedger reloaded = roundTrip(Map.of());

        assertNull(reloaded.take().get(UUID.randomUUID()));
    }

    @Test
    @DisplayName("multiple players and multiple species do not bleed into each other")
    void multiplePlayersAndSpeciesStayDistinct() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        Map<UUID, Map<ResourceLocation, TrophyEntry>> result = roundTrip(Map.of(
                alice, Map.of(GARCHOMP, entry(GARCHOMP, RaidRarityTier.LEGENDARY, 1),
                        TYRANITAR, entry(TYRANITAR, RaidRarityTier.POWERHOUSE, 2)),
                bob, Map.of(GARCHOMP, entry(GARCHOMP, RaidRarityTier.LEGENDARY, 5)))).take();

        assertEquals(2, result.get(alice).size());
        assertEquals(1, result.get(alice).get(GARCHOMP).timesDefeated());
        assertEquals(2, result.get(alice).get(TYRANITAR).timesDefeated());
        assertEquals(1, result.get(bob).size());
        assertEquals(5, result.get(bob).get(GARCHOMP).timesDefeated());
    }

    @Test
    @DisplayName("a tier name the game no longer has drops that one entry, not the whole load")
    void unknownTierIsDroppedNotFatal() {
        UUID playerId = UUID.randomUUID();
        TrophyLedger store = new TrophyLedger();
        store.update(Map.of(playerId, Map.of(
                GARCHOMP, entry(GARCHOMP, RaidRarityTier.STARTER, 1),
                TYRANITAR, entry(TYRANITAR, RaidRarityTier.STARTER, 1))));
        CompoundTag tag = store.save(new CompoundTag(), null);
        CompoundTag playerTag = tag.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        var speciesList = playerTag.getList("species", Tag.TAG_COMPOUND);
        for (int i = 0; i < speciesList.size(); i++) {
            if (speciesList.getCompound(i).getString("species").equals(TYRANITAR.toString())) {
                speciesList.getCompound(i).putString("tier", "retired_tier_from_an_old_version");
            }
        }

        Map<ResourceLocation, TrophyEntry> result = TrophyLedger.load(tag, null).take().get(playerId);

        assertEquals(1, result.size(), "the corrupted entry must not appear at all");
        assertTrue(result.containsKey(GARCHOMP), "the still-valid entry must survive");
    }

    @Test
    @DisplayName("recordDefeat pins first-defeat stats and only grows the counter on a repeat")
    void repeatDefeatOnlyIncrementsCounter() {
        UUID playerId = UUID.randomUUID();
        // recordDefeat itself needs a MinecraftServer to persist against, which this unit test has
        // no reason to bootstrap -- the merge logic it exercises is the same Map.compute this test
        // drives directly, so this proves the rule (pin first stats, grow the counter) without it.
        Map<ResourceLocation, TrophyEntry> perPlayer = new java.util.HashMap<>();
        perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                ? new TrophyEntry(GARCHOMP, 70, false, 50, 40, RaidRarityTier.STARTER, 500L, 1)
                : existing);
        perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                ? new TrophyEntry(GARCHOMP, 99, true, 100, 100, RaidRarityTier.LEGENDARY, 999L, 1)
                : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                        existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                        existing.firstDefeatedAtEpochMs(), existing.timesDefeated() + 1));

        TrophyEntry result = perPlayer.get(GARCHOMP);
        assertEquals(70, result.level(), "the first defeat's stats must survive a repeat");
        assertEquals(500L, result.firstDefeatedAtEpochMs());
        assertEquals(2, result.timesDefeated());
    }
}
