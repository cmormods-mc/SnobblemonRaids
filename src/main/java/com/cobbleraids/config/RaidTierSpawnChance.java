package com.cobbleraids.config;

import com.cobbleraids.RaidLog;

/**
 * Per-tier probability that a selected natural spawn actually happens. This is a <em>rate</em>
 * control, which {@link RaidTierWeights} deliberately is not.
 *
 * <p>Tier weights are a mix selector: they are renormalised across whichever tiers currently have
 * eligible definitions, so lowering the starter weight does not produce fewer raids -- it converts
 * starter spawns into powerhouse and legendary ones, making the rare tiers <em>more</em> frequent.
 * That is the opposite of what a server owner asking to "see fewer common raids" wants, and the
 * same is true of the other levers that look like they should help: a definition's cooldown or
 * max_concurrent only removes it from the eligible pool, after which the weights renormalise around
 * it and a spawn of some other tier happens anyway.
 *
 * <p>This roll is applied after the tier has been chosen, and a failed roll means <em>no raid this
 * attempt</em> -- never a re-roll into another tier. That is what keeps the tiers independent:
 * turning starter down leaves the absolute rate of legendary and mythical exactly where it was.
 */
public record RaidTierSpawnChance(double starter, double powerhouse, double legendary, double mythical) {
    public RaidTierSpawnChance {
        validate("starter", starter);
        validate("powerhouse", powerhouse);
        validate("legendary", legendary);
        validate("mythical", mythical);
        if (starter <= 0.0 && powerhouse <= 0.0 && legendary <= 0.0 && mythical <= 0.0) {
            // Not fatal: "0 for every tier" is a coherent way to say "no wild raids", and admin
            // spawns still work. Loud, though, because it is far more likely to be a mistake.
            RaidLog.warn("every natural_spawning.tier_spawn_chance is 0,"
                    + " so no wild raid will ever spawn. Set natural_spawning.enabled to false if that"
                    + " is intended, or raise a tier's chance.");
        }
    }

    /** Every tier at full rate, so an existing server.json without this block behaves as before. */
    public static RaidTierSpawnChance defaults() {
        return new RaidTierSpawnChance(1.0, 1.0, 1.0, 1.0);
    }

    public double chanceFor(RaidRarityTier tier) {
        return switch (tier) {
            case STARTER -> starter;
            case POWERHOUSE -> powerhouse;
            case LEGENDARY -> legendary;
            case MYTHICAL -> mythical;
        };
    }

    private static void validate(String name, double chance) {
        if (!(chance >= 0.0) || chance > 1.0) {
            throw new IllegalArgumentException("natural_spawning.tier_spawn_chance." + name + " must be 0..1");
        }
    }
}
