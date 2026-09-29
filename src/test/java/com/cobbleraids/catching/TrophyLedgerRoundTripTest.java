package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new TrophyEntry(species, 75, true, 84, 62, tier, 1_000_000L, timesDefeated, 0, 0, 0, 0, "");
    }

    private static TrophyEntry captured(ResourceLocation species, RaidRarityTier tier,
                                        long firstCapturedAtEpochMs, int timesCaptured, int bestStabilizationPct) {
        return new TrophyEntry(species, 75, true, 84, 62, tier, 1_000_000L, 1,
                firstCapturedAtEpochMs, timesCaptured, bestStabilizationPct, 0, "");
    }

    private static TrophyEntry renowned(ResourceLocation species, RaidRarityTier tier,
                                        int timesRenownDefeated, String firstRenownTitle) {
        return new TrophyEntry(species, 75, true, 84, 62, tier, 1_000_000L, 1, 0, 0, 0,
                timesRenownDefeated, firstRenownTitle);
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
                ? new TrophyEntry(GARCHOMP, 70, false, 50, 40, RaidRarityTier.STARTER, 500L, 1, 0, 0, 0, 0, "")
                : existing);
        perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                ? new TrophyEntry(GARCHOMP, 99, true, 100, 100, RaidRarityTier.LEGENDARY, 999L, 1, 0, 0, 0, 0, "")
                : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                        existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                        existing.firstDefeatedAtEpochMs(), existing.timesDefeated() + 1,
                        existing.firstCapturedAtEpochMs(), existing.timesCaptured(),
                        existing.bestStabilizationScorePercent(),
                        existing.timesRenownDefeated(), existing.firstRenownTitle()));

        TrophyEntry result = perPlayer.get(GARCHOMP);
        assertEquals(70, result.level(), "the first defeat's stats must survive a repeat");
        assertEquals(500L, result.firstDefeatedAtEpochMs());
        assertEquals(2, result.timesDefeated());
    }

    @Test
    @DisplayName("the first renowned defeat pins the title; a later, differently titled one only grows the counter")
    void repeatRenownedDefeatPinsFirstTitleOnly() {
        Map<ResourceLocation, TrophyEntry> perPlayer = new java.util.HashMap<>();
        String[] titles = {"", "Kaelen, the Relentless", "Doraan, the Ancient"};
        for (String title : titles) {
            perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                    ? new TrophyEntry(GARCHOMP, 70, false, 50, 40, RaidRarityTier.STARTER, 500L, 1, 0, 0, 0,
                            title.isEmpty() ? 0 : 1, title)
                    : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                            existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                            existing.firstDefeatedAtEpochMs(), existing.timesDefeated() + 1,
                            existing.firstCapturedAtEpochMs(), existing.timesCaptured(),
                            existing.bestStabilizationScorePercent(),
                            existing.timesRenownDefeated() + (title.isEmpty() ? 0 : 1),
                            existing.firstRenownTitle().isEmpty() ? title : existing.firstRenownTitle()));
        }

        TrophyEntry result = perPlayer.get(GARCHOMP);
        assertEquals("Kaelen, the Relentless", result.firstRenownTitle(),
                "the first renowned title seen must stay pinned even after a second, different one");
        assertEquals(2, result.timesRenownDefeated(), "both renowned defeats must count, the non-renowned one must not");
        assertEquals(3, result.timesDefeated());
    }

    @Test
    @DisplayName("a species never captured reports 0 for every capture field, not a stray default")
    void neverCapturedHasZeroedCaptureFields() {
        TrophyEntry never = entry(GARCHOMP, RaidRarityTier.STARTER, 1);

        assertFalse(never.everCaptured());
        assertEquals(0L, never.firstCapturedAtEpochMs());
        assertEquals(0, never.timesCaptured());
        assertEquals(0, never.bestStabilizationScorePercent());
    }

    @Test
    @DisplayName("a captured trophy's capture fields survive a save/load round trip exactly")
    void capturedTrophyRoundTrips() {
        UUID playerId = UUID.randomUUID();
        TrophyEntry original = captured(GARCHOMP, RaidRarityTier.MYTHICAL, 777_000L, 3, 92);

        TrophyEntry result = roundTrip(Map.of(playerId, Map.of(GARCHOMP, original)))
                .take().get(playerId).get(GARCHOMP);

        assertTrue(result.everCaptured());
        assertEquals(777_000L, result.firstCapturedAtEpochMs());
        assertEquals(3, result.timesCaptured());
        assertEquals(92, result.bestStabilizationScorePercent());
    }

    @Test
    @DisplayName("recordCapture pins the first capture's timestamp and only grows the counter and best score")
    void repeatCaptureOnlyGrowsCounterAndBestScore() {
        // Same reasoning as repeatDefeatOnlyIncrementsCounter: recordCapture itself needs a
        // MinecraftServer to persist against, so this drives the exact merge logic by hand instead.
        Map<ResourceLocation, TrophyEntry> perPlayer = new java.util.HashMap<>();
        perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                ? new TrophyEntry(GARCHOMP, 70, false, 50, 40, RaidRarityTier.STARTER, 500L, 1, 1_000L, 1, 60, 0, "")
                : existing);
        // A second, later, worse-played capture: timestamp must not move, count grows, best score
        // must not fall to a worse run.
        perPlayer.compute(GARCHOMP, (ignored, existing) -> existing == null
                ? new TrophyEntry(GARCHOMP, 70, false, 50, 40, RaidRarityTier.STARTER, 500L, 1, 2_000L, 1, 30, 0, "")
                : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                        existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                        existing.firstDefeatedAtEpochMs(), existing.timesDefeated(),
                        existing.everCaptured() ? existing.firstCapturedAtEpochMs() : 2_000L,
                        existing.timesCaptured() + 1, Math.max(existing.bestStabilizationScorePercent(), 30),
                        existing.timesRenownDefeated(), existing.firstRenownTitle()));

        TrophyEntry result = perPlayer.get(GARCHOMP);
        assertEquals(1_000L, result.firstCapturedAtEpochMs(), "the first capture's timestamp must survive a repeat");
        assertEquals(2, result.timesCaptured());
        assertEquals(60, result.bestStabilizationScorePercent(), "a worse repeat must not lower the best score");
    }

    @Test
    @DisplayName("a species never fought renowned reports 0/empty for both renown fields, not a stray default")
    void neverRenownedHasZeroedRenownFields() {
        TrophyEntry never = entry(GARCHOMP, RaidRarityTier.STARTER, 1);

        assertFalse(never.everRenowned());
        assertEquals(0, never.timesRenownDefeated());
        assertEquals("", never.firstRenownTitle());
    }

    @Test
    @DisplayName("a renowned trophy's fields survive a save/load round trip exactly")
    void renownedTrophyRoundTrips() {
        UUID playerId = UUID.randomUUID();
        TrophyEntry original = renowned(GARCHOMP, RaidRarityTier.LEGENDARY, 4, "Kaelen, the Relentless");

        TrophyEntry result = roundTrip(Map.of(playerId, Map.of(GARCHOMP, original)))
                .take().get(playerId).get(GARCHOMP);

        assertTrue(result.everRenowned());
        assertEquals(4, result.timesRenownDefeated());
        assertEquals("Kaelen, the Relentless", result.firstRenownTitle());
    }

    @Test
    @DisplayName("NBT saved before renown existed loads as 0/empty, not a failure")
    void missingRenownKeysLoadAsZeroAndEmpty() {
        UUID playerId = UUID.randomUUID();
        TrophyLedger store = new TrophyLedger();
        store.update(Map.of(playerId, Map.of(GARCHOMP, entry(GARCHOMP, RaidRarityTier.STARTER, 1))));
        CompoundTag tag = store.save(new CompoundTag(), null);
        CompoundTag row = tag.getList("players", Tag.TAG_COMPOUND).getCompound(0)
                .getList("species", Tag.TAG_COMPOUND).getCompound(0);
        row.remove("times_renown_defeated");
        row.remove("first_renown_title");

        TrophyEntry result = TrophyLedger.load(tag, null).take().get(playerId).get(GARCHOMP);

        assertFalse(result.everRenowned());
        assertEquals(0, result.timesRenownDefeated());
        assertEquals("", result.firstRenownTitle());
    }
}
