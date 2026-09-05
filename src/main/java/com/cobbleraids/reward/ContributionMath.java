package com.cobbleraids.reward;

import java.util.*;

/** Pure contribution math kept independent of Minecraft for deterministic unit validation. */
public final class ContributionMath {
    private ContributionMath() {}

    /** Damage-share percentage. Positive eligible contributions are normalized to exactly 100% (within FP tolerance). */
    public static <K> Map<K, Double> percentages(Map<K, ? extends Number> damage, Collection<K> eligible) {
        LinkedHashMap<K, Double> result = new LinkedHashMap<>();
        double total = 0.0;
        for (K key : eligible) total += Math.max(0.0, number(damage.get(key)));
        for (K key : eligible) {
            double value = Math.max(0.0, number(damage.get(key)));
            result.put(key, total <= 0.0 ? 0.0 : (value / total) * 100.0);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Highest matching threshold wins; thresholds need not be sorted. */
    public static int bonusRolls(double percentage, Collection<Threshold> thresholds) {
        int rolls = 0;
        double best = -1.0;
        for (Threshold threshold : thresholds) {
            if (percentage + 1.0e-9 >= threshold.minPercentage() && threshold.minPercentage() >= best) {
                best = threshold.minPercentage();
                rolls = threshold.rolls();
            }
        }
        return rolls;
    }

    private static double number(Number value) { return value == null ? 0.0 : value.doubleValue(); }

    public record Threshold(double minPercentage, int rolls) {}
}
