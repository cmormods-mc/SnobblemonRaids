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
        int bossesCaught
) {
    public static final RaidPlayerRecord EMPTY =
            new RaidPlayerRecord(0, Map.of(), Map.of(), 0.0, 0);

    public RaidPlayerRecord {
        // Built key-first rather than with EnumMap's copy constructor: that one throws
        // "Specified map is empty" when handed an empty map that is not itself an EnumMap, which
        // is exactly what EMPTY passes and what an unmodifiable wrapper looks like on the way back
        // in. A record every mechanic reads must not be able to fail at class initialisation.
        EnumMap<RaidRarityTier, Integer> tiers = new EnumMap<>(RaidRarityTier.class);
        tiers.putAll(winsByTier);
        winsByTier = Collections.unmodifiableMap(tiers);
        defeatsBySpecies = Collections.unmodifiableMap(new LinkedHashMap<>(defeatsBySpecies));
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
                totalContribution + contribution, bossesCaught);
    }

    public RaidPlayerRecord withCatch() {
        return new RaidPlayerRecord(raidsWon, winsByTier, defeatsBySpecies, totalContribution, bossesCaught + 1);
    }
}
