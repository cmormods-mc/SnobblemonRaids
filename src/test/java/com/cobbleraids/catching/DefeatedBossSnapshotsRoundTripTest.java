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
 * Proves the outer wrapper (player, species, the cached display scalars) survives a save/load round
 * trip exactly, the same way {@code RaidPlayerRecordsRoundTripTest} does for player records. The
 * nested {@code pokemonNbt} blob itself is stood in for with a small synthetic opaque tag rather
 * than a real Cobblemon-produced one -- this class never interprets that field, only stores and
 * returns it, so a real blob would prove nothing more about this class than an opaque one does; the
 * real round trip through Cobblemon's own codec is covered live instead.
 */
class DefeatedBossSnapshotsRoundTripTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.parse("cobblemon:garchomp");
    private static final ResourceLocation TYRANITAR = ResourceLocation.parse("cobblemon:tyranitar");

    private static CompoundTag opaquePokemonTag(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", marker);
        return tag;
    }

    private static BossSnapshot snapshot(ResourceLocation species, RaidRarityTier tier, String marker) {
        return new BossSnapshot(species, 75, true, 84, 62, tier, opaquePokemonTag(marker), 1_000_000L);
    }

    private static DefeatedBossSnapshots roundTrip(Map<UUID, Map<ResourceLocation, BossSnapshot>> records) {
        DefeatedBossSnapshots store = new DefeatedBossSnapshots();
        store.update(records);
        CompoundTag tag = store.save(new CompoundTag(), null);
        return DefeatedBossSnapshots.load(tag, null);
    }

    @Test
    @DisplayName("a fully populated snapshot survives a save/load round trip exactly")
    void fullyPopulatedSnapshotRoundTrips() {
        UUID playerId = UUID.randomUUID();
        BossSnapshot original = snapshot(GARCHOMP, RaidRarityTier.LEGENDARY, "the-real-boss");

        DefeatedBossSnapshots reloaded = roundTrip(Map.of(playerId, Map.of(GARCHOMP, original)));

        BossSnapshot result = reloaded.take().get(playerId).get(GARCHOMP);
        assertEquals(original.species(), result.species());
        assertEquals(original.level(), result.level());
        assertEquals(original.shiny(), result.shiny());
        assertEquals(original.ivPercent(), result.ivPercent());
        assertEquals(original.evPercent(), result.evPercent());
        assertEquals(original.rarityTier(), result.rarityTier());
        assertEquals(original.capturedAtEpochMs(), result.capturedAtEpochMs());
        assertEquals("the-real-boss", result.pokemonNbt().getString("marker"));
    }

    @Test
    @DisplayName("an untouched player has no personal shop page at all, not an empty one")
    void unknownPlayerHasNoSnapshots() {
        DefeatedBossSnapshots reloaded = roundTrip(Map.of());

        assertNull(reloaded.take().get(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a single species slot round-trips as exactly one entry, not a duplicate")
    void oneSlotPerSpeciesStaysOneEntry() {
        // The "always overwrite" contract itself (decision: a new defeat replaces the old snapshot
        // unconditionally) lives in DefeatedBossSnapshots.put, a one-line Map.put -- trivial enough
        // that live verification covers it (see the plan's re-defeat overwrite check). What is
        // worth pinning here is that the save/load round trip itself never turns one map entry into
        // more than one row on disk.
        UUID playerId = UUID.randomUUID();
        BossSnapshot current = snapshot(GARCHOMP, RaidRarityTier.STARTER, "current-defeat");

        DefeatedBossSnapshots reloaded = roundTrip(Map.of(playerId, Map.of(GARCHOMP, current)));

        Map<ResourceLocation, BossSnapshot> result = reloaded.take().get(playerId);
        assertEquals(1, result.size());
        assertEquals("current-defeat", result.get(GARCHOMP).pokemonNbt().getString("marker"));
    }

    @Test
    @DisplayName("multiple players and multiple species do not bleed into each other")
    void multiplePlayersAndSpeciesStayDistinct() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        BossSnapshot aliceGarchomp = snapshot(GARCHOMP, RaidRarityTier.LEGENDARY, "alice-garchomp");
        BossSnapshot aliceTyranitar = snapshot(TYRANITAR, RaidRarityTier.POWERHOUSE, "alice-tyranitar");
        BossSnapshot bobGarchomp = snapshot(GARCHOMP, RaidRarityTier.LEGENDARY, "bob-garchomp");

        Map<UUID, Map<ResourceLocation, BossSnapshot>> result = roundTrip(Map.of(
                alice, Map.of(GARCHOMP, aliceGarchomp, TYRANITAR, aliceTyranitar),
                bob, Map.of(GARCHOMP, bobGarchomp))).take();

        assertEquals(2, result.get(alice).size());
        assertEquals("alice-garchomp", result.get(alice).get(GARCHOMP).pokemonNbt().getString("marker"));
        assertEquals("alice-tyranitar", result.get(alice).get(TYRANITAR).pokemonNbt().getString("marker"));
        assertEquals(1, result.get(bob).size());
        assertEquals("bob-garchomp", result.get(bob).get(GARCHOMP).pokemonNbt().getString("marker"));
    }

    @Test
    @DisplayName("a tier name the game no longer has drops that one slot, not the whole load")
    void unknownTierIsDroppedNotFatal() {
        UUID playerId = UUID.randomUUID();
        DefeatedBossSnapshots store = new DefeatedBossSnapshots();
        store.update(Map.of(playerId, Map.of(
                GARCHOMP, snapshot(GARCHOMP, RaidRarityTier.STARTER, "kept"),
                TYRANITAR, snapshot(TYRANITAR, RaidRarityTier.STARTER, "will-be-corrupted"))));
        CompoundTag tag = store.save(new CompoundTag(), null);
        CompoundTag playerTag = tag.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        var speciesList = playerTag.getList("species", Tag.TAG_COMPOUND);
        for (int i = 0; i < speciesList.size(); i++) {
            if (speciesList.getCompound(i).getString("species").equals(TYRANITAR.toString())) {
                speciesList.getCompound(i).putString("tier", "retired_tier_from_an_old_version");
            }
        }

        Map<ResourceLocation, BossSnapshot> result = DefeatedBossSnapshots.load(tag, null).take().get(playerId);

        assertEquals(1, result.size(), "the corrupted slot must not appear at all");
        assertTrue(result.containsKey(GARCHOMP), "the still-valid slot must survive");
    }
}
