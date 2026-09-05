package com.cobbleraids.spawn;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidTierWeights;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

/** Selects an aggregate rarity tier first, then performs weighted species selection inside it. */
public final class RaidTierSelector {
    private RaidTierSelector() {}

    public static <T> T select(
            List<T> eligible,
            Function<T, RaidRarityTier> tierOf,
            ToIntFunction<T> definitionWeight,
            RaidTierWeights tierWeights,
            RandomGenerator random
    ) {
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(tierOf);
        Objects.requireNonNull(definitionWeight);
        Objects.requireNonNull(tierWeights);
        Objects.requireNonNull(random);
        if (eligible.isEmpty()) return null;

        EnumMap<RaidRarityTier, List<T>> grouped = group(eligible, tierOf);
        long tierTotal = 0L;
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            if (!grouped.get(tier).isEmpty()) tierTotal += tierWeights.weightFor(tier);
        }
        if (tierTotal <= 0L) return null;

        long roll = random.nextLong(tierTotal);
        long cursor = 0L;
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            if (grouped.get(tier).isEmpty()) continue;
            cursor += tierWeights.weightFor(tier);
            if (roll < cursor) {
                return selectWithinTier(grouped.get(tier), definitionWeight, random);
            }
        }
        return null;
    }

    public static <T> Map<RaidRarityTier, Integer> counts(
            List<T> eligible,
            Function<T, RaidRarityTier> tierOf
    ) {
        EnumMap<RaidRarityTier, List<T>> grouped = group(eligible, tierOf);
        EnumMap<RaidRarityTier, Integer> result = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            result.put(tier, grouped.get(tier).size());
        }
        return result;
    }

    public static Map<RaidRarityTier, Double> normalizedPercentages(
            Map<RaidRarityTier, Integer> counts,
            RaidTierWeights weights
    ) {
        long total = 0L;
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            if (counts.getOrDefault(tier, 0) > 0) total += weights.weightFor(tier);
        }

        EnumMap<RaidRarityTier, Double> result = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            double percentage = total > 0L && counts.getOrDefault(tier, 0) > 0
                    ? weights.weightFor(tier) * 100.0 / total
                    : 0.0;
            result.put(tier, percentage);
        }
        return result;
    }

    private static <T> EnumMap<RaidRarityTier, List<T>> group(
            List<T> values,
            Function<T, RaidRarityTier> tierOf
    ) {
        EnumMap<RaidRarityTier, List<T>> grouped = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) grouped.put(tier, new ArrayList<>());
        for (T value : values) grouped.get(Objects.requireNonNull(tierOf.apply(value))).add(value);
        return grouped;
    }

    private static <T> T selectWithinTier(
            List<T> values,
            ToIntFunction<T> weightOf,
            RandomGenerator random
    ) {
        long total = 0L;
        for (T value : values) {
            int weight = weightOf.applyAsInt(value);
            if (weight < 1) throw new IllegalArgumentException("definition weight must be positive");
            total += weight;
        }

        long roll = random.nextLong(total);
        long cursor = 0L;
        for (T value : values) {
            cursor += weightOf.applyAsInt(value);
            if (roll < cursor) return value;
        }
        return values.get(values.size() - 1);
    }
}
