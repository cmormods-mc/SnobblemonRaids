package com.cobbleraids.config;

import java.util.Locale;

/** Aggregate natural-spawn tier selected before an individual raid definition. */
public enum RaidRarityTier {
    STARTER("Starter"),
    POWERHOUSE("Powerhouse"),
    LEGENDARY("Legendary"),
    MYTHICAL("Mythical");

    private final String displayName;

    RaidRarityTier(String displayName) {
        this.displayName = displayName;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public static RaidRarityTier parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rarity_tier cannot be blank");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
