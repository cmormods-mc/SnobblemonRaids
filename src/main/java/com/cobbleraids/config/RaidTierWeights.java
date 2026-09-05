package com.cobbleraids.config;

/** Configurable aggregate weights for the four natural-spawn rarity tiers. */
public record RaidTierWeights(int starter, int powerhouse, int legendary, int mythical) {
    private static final int MAX_WEIGHT = 1_000_000;

    public RaidTierWeights {
        validate("starter", starter);
        validate("powerhouse", powerhouse);
        validate("legendary", legendary);
        validate("mythical", mythical);
        if ((long) starter + powerhouse + legendary + mythical <= 0L) {
            throw new IllegalArgumentException("natural_spawning.tier_weights must enable at least one tier");
        }
    }

    public static RaidTierWeights defaults() {
        return new RaidTierWeights(70, 20, 8, 2);
    }

    public int weightFor(RaidRarityTier tier) {
        return switch (tier) {
            case STARTER -> starter;
            case POWERHOUSE -> powerhouse;
            case LEGENDARY -> legendary;
            case MYTHICAL -> mythical;
        };
    }

    private static void validate(String name, int weight) {
        if (weight < 0 || weight > MAX_WEIGHT) {
            throw new IllegalArgumentException("natural_spawning.tier_weights." + name + " must be 0..1000000");
        }
    }
}
