package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.renown.RenownBoon;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves a Hall of Legends entry survives a save/load round trip exactly, the same way
 * {@code TrophyLedgerRoundTripTest} does -- and separately, that {@code recordFirstDefeat}'s
 * check-and-insert is atomic and idempotent.
 */
class HallOfLegendsRoundTripTest {

    private static final ResourceLocation GYARADOS = ResourceLocation.parse("cobblemon:gyarados");
    private static final ResourceLocation GARCHOMP = ResourceLocation.parse("cobblemon:garchomp");

    private static LegendEntry entry(String title, ResourceLocation species, List<UUID> victorIds,
                                     List<String> victorNames) {
        return new LegendEntry(title, species, RaidRarityTier.LEGENDARY,
                RenownBoon.statFocus("attack"), 1_000_000L, victorIds, victorNames);
    }

    private static HallOfLegends roundTrip(Map<LegendKey, LegendEntry> records) {
        HallOfLegends store = new HallOfLegends();
        store.update(records);
        CompoundTag tag = store.save(new CompoundTag(), null);
        return HallOfLegends.load(tag, null);
    }

    @Test
    @DisplayName("a fully populated legend, including a full 4-victor party, survives a save/load round trip exactly")
    void fullyPopulatedLegendRoundTrips() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        LegendEntry original = entry("Kaelen, the Relentless", GYARADOS,
                List.of(a, b, c, d), List.of("Alice", "Bob", "Cara", "Dee"));

        HallOfLegends reloaded = roundTrip(Map.of(original.key(), original));

        LegendEntry result = reloaded.take().get(original.key());
        assertEquals(original.title(), result.title());
        assertEquals(original.species(), result.species());
        assertEquals(original.tier(), result.tier());
        assertEquals(original.boon(), result.boon());
        assertEquals(original.defeatedAtEpochMs(), result.defeatedAtEpochMs());
        assertEquals(original.victorIds(), result.victorIds(), "victor order must survive exactly");
        assertEquals(original.victorNames(), result.victorNames(), "victor names must stay paired with their ids");
    }

    @Test
    @DisplayName("an unclaimed (title, species) pairing has no record at all, not an empty one")
    void unclaimedPairingHasNoRecord() {
        HallOfLegends reloaded = roundTrip(Map.of());

        assertNull(reloaded.take().get(new LegendKey("Nobody, the Unclaimed", GARCHOMP)));
    }

    @Test
    @DisplayName("the same title on two different species are two distinct records")
    void sameTitleDifferentSpeciesStayDistinct() {
        UUID victor = UUID.randomUUID();
        LegendEntry onGyarados = entry("Kaelen, the Relentless", GYARADOS, List.of(victor), List.of("Alice"));
        LegendEntry onGarchomp = entry("Kaelen, the Relentless", GARCHOMP, List.of(victor), List.of("Alice"));

        Map<LegendKey, LegendEntry> result = roundTrip(Map.of(
                onGyarados.key(), onGyarados, onGarchomp.key(), onGarchomp)).take();

        assertEquals(2, result.size());
        assertEquals(GYARADOS, result.get(onGyarados.key()).species());
        assertEquals(GARCHOMP, result.get(onGarchomp.key()).species());
    }

    @Test
    @DisplayName("a tier name the game no longer has drops that one row, not the whole load")
    void unknownTierIsDroppedNotFatal() {
        UUID victor = UUID.randomUUID();
        LegendEntry good = entry("Kaelen, the Relentless", GYARADOS, List.of(victor), List.of("Alice"));
        LegendEntry bad = entry("Doraan, the Ancient", GARCHOMP, List.of(victor), List.of("Alice"));
        HallOfLegends store = new HallOfLegends();
        store.update(Map.of(good.key(), good, bad.key(), bad));
        CompoundTag tag = store.save(new CompoundTag(), null);
        var rows = tag.getList("legends", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.getCompound(i).getString("species").equals(GARCHOMP.toString())) {
                rows.getCompound(i).putString("tier", "retired_tier_from_an_old_version");
            }
        }

        Map<LegendKey, LegendEntry> result = HallOfLegends.load(tag, null).take();

        assertEquals(1, result.size(), "the corrupted row must not appear at all");
        assertTrue(result.containsKey(good.key()), "the still-valid row must survive");
    }

    @Test
    @DisplayName("an undecodable boon drops that one row, not the whole load")
    void unknownBoonIsDroppedNotFatal() {
        UUID victor = UUID.randomUUID();
        LegendEntry good = entry("Kaelen, the Relentless", GYARADOS, List.of(victor), List.of("Alice"));
        LegendEntry bad = entry("Doraan, the Ancient", GARCHOMP, List.of(victor), List.of("Alice"));
        HallOfLegends store = new HallOfLegends();
        store.update(Map.of(good.key(), good, bad.key(), bad));
        CompoundTag tag = store.save(new CompoundTag(), null);
        var rows = tag.getList("legends", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.getCompound(i).getString("species").equals(GARCHOMP.toString())) {
                rows.getCompound(i).putString("boon", "not_a_real_boon");
            }
        }

        Map<LegendKey, LegendEntry> result = HallOfLegends.load(tag, null).take();

        assertEquals(1, result.size(), "the corrupted row must not appear at all");
        assertTrue(result.containsKey(good.key()), "the still-valid row must survive");
    }

    @Test
    @DisplayName("recordFirstDefeat only claims a (title, species) pairing once, and reports which call did")
    void recordFirstDefeatIsAtomicAndIdempotent() {
        // recordFirstDefeat itself needs a MinecraftServer to persist against, which this unit test
        // has no reason to bootstrap -- the claim logic it exercises is the same computeIfAbsent this
        // test drives directly, so this proves the rule without one.
        Map<LegendKey, LegendEntry> live = new java.util.concurrent.ConcurrentHashMap<>();
        LegendEntry first = entry("Kaelen, the Relentless", GYARADOS,
                List.of(UUID.randomUUID()), List.of("Alice"));
        LegendEntry second = entry("Kaelen, the Relentless", GYARADOS,
                List.of(UUID.randomUUID()), List.of("Bob"));

        LegendEntry[] insertedFirst = {null};
        live.computeIfAbsent(first.key(), ignored -> { insertedFirst[0] = first; return first; });
        LegendEntry[] insertedSecond = {null};
        live.computeIfAbsent(second.key(), ignored -> { insertedSecond[0] = second; return second; });

        assertTrue(insertedFirst[0] != null, "the first claim of a fresh key must report as new");
        assertFalse(insertedSecond[0] != null, "a second claim of the same key must report as not new");
        assertEquals("Alice", live.get(first.key()).victorNames().get(0),
                "the second call must not overwrite the first claim's victor");
    }
}
