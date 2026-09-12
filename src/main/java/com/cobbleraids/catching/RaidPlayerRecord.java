package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * One player's raid history: what they have beaten, how often, and how hard they pulled.
 *
 * <p>This is the substrate every catch mechanic needs and none of them own. A points economy reads
 * it to decide what a raid paid out; a reputation system reads it to decide how close a player is
 * to earning a catch; a per-species progression reads {@link #defeatsOf} directly. Keeping the
 * history separate from the rule that interprets it is the whole point -- the rule can change
 * without the history being rebuilt, and a server that changes its mind keeps its players' progress.
 *
 * <p>Immutable. {@link RaidPlayerRecords} owns the live copies and swaps a new one in per win.
 */
public record RaidPlayerRecord(
        int raidsWon,
        Map<RaidRarityTier, Integer> winsByTier,
        Map<ResourceLocation, Integer> defeatsBySpecies,
        double totalContribution,
        int bossesCaught,
        int raidsSinceMegaStone,
        int raidPoints,
        Map<String, RaidPurchaseTally> purchases
) {
    public static final RaidPlayerRecord EMPTY =
            new RaidPlayerRecord(0, Map.of(), Map.of(), 0.0, 0, 0, 0, Map.of());

    public RaidPlayerRecord {
        // Built key-first rather than with EnumMap's copy constructor: that one throws
        // "Specified map is empty" when handed an empty map that is not itself an EnumMap, which
        // is exactly what EMPTY passes and what an unmodifiable wrapper looks like on the way back
        // in. A record every mechanic reads must not be able to fail at class initialisation.
        EnumMap<RaidRarityTier, Integer> tiers = new EnumMap<>(RaidRarityTier.class);
        tiers.putAll(winsByTier);
        winsByTier = Collections.unmodifiableMap(tiers);
        defeatsBySpecies = Collections.unmodifiableMap(new LinkedHashMap<>(defeatsBySpecies));
        // Insertion-ordered so that saving a record nobody changed produces the same bytes, which
        // keeps the world save from churning on every autosave.
        purchases = Collections.unmodifiableMap(
                new LinkedHashMap<>(purchases == null ? Map.of() : purchases));
    }

    public int winsIn(RaidRarityTier tier) {
        return winsByTier.getOrDefault(tier, 0);
    }

    /**
     * How many times this player has beaten one particular raid species. The natural input for a
     * "you learn its patterns" progression, and the reason species is recorded at all rather than
     * just a running total.
     */
    public int defeatsOf(ResourceLocation definitionId) {
        return defeatsBySpecies.getOrDefault(definitionId, 0);
    }

    /** Mean damage share across every raid won, or 0 before the first. */
    public double averageContribution() {
        return raidsWon == 0 ? 0.0 : totalContribution / raidsWon;
    }

    public RaidPlayerRecord withWin(RaidRarityTier tier, ResourceLocation definitionId, double contribution) {
        EnumMap<RaidRarityTier, Integer> tiers = new EnumMap<>(RaidRarityTier.class);
        tiers.putAll(winsByTier);
        tiers.merge(tier, 1, Integer::sum);
        LinkedHashMap<ResourceLocation, Integer> species = new LinkedHashMap<>(defeatsBySpecies);
        species.merge(definitionId, 1, Integer::sum);
        return new RaidPlayerRecord(raidsWon + 1, tiers, species,
                totalContribution + contribution, bossesCaught, raidsSinceMegaStone, raidPoints, purchases);
    }

    public RaidPlayerRecord withCatch() {
        return new RaidPlayerRecord(raidsWon, winsByTier, defeatsBySpecies, totalContribution,
                bossesCaught + 1, raidsSinceMegaStone, raidPoints, purchases);
    }

    /**
     * The same record with {@code delta} added to its Raid Points, floored at zero.
     *
     * <p>Floored rather than allowed negative: a balance is a thing players spend, and a debt is
     * not a state this mod has any way to resolve. A spend that would overdraw is refused by
     * RaidPointsStore before it reaches here.
     */
    public RaidPlayerRecord withPoints(int delta) {
        long updated = (long) raidPoints + delta;
        int clamped = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, updated));
        return new RaidPlayerRecord(raidsWon, winsByTier, defeatsBySpecies, totalContribution,
                bossesCaught, raidsSinceMegaStone, clamped, purchases);
    }

    /** How many times a limited entry has been bought, and on which day. Never null. */
    public RaidPurchaseTally purchasesOf(String entryId) {
        return purchases.getOrDefault(entryId, RaidPurchaseTally.NONE);
    }

    /**
     * The same record with one more purchase of {@code entryId} counted against {@code window}.
     *
     * <p>Only limited entries are recorded. Counting an unlimited one would grow this map without
     * bound for a player who buys Poke Balls every evening, and it is written into the world save.
     */
    public RaidPlayerRecord withPurchase(String entryId, long window) {
        LinkedHashMap<String, RaidPurchaseTally> updated = new LinkedHashMap<>(purchases);
        updated.put(entryId, purchasesOf(entryId).increment(window));
        return new RaidPlayerRecord(raidsWon, winsByTier, defeatsBySpecies, totalContribution,
                bossesCaught, raidsSinceMegaStone, raidPoints, updated);
    }

    /**
     * Drops tallies whose window has passed, so a long-lived record does not accumulate a row for
     * every daily entry a player has ever touched.
     *
     * <p>Purely housekeeping: {@link RaidPurchaseTally#countOn} already reads a stale tally as zero,
     * so this changes what is stored and never what is allowed.
     */
    public RaidPlayerRecord prunePurchases(long window) {
        LinkedHashMap<String, RaidPurchaseTally> kept = new LinkedHashMap<>();
        purchases.forEach((id, tally) -> {
            if (tally.day() == window || tally.day() == 0L) kept.put(id, tally);
        });
        return kept.size() == purchases.size() ? this
                : new RaidPlayerRecord(raidsWon, winsByTier, defeatsBySpecies, totalContribution,
                        bossesCaught, raidsSinceMegaStone, raidPoints, kept);
    }
}
