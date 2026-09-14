package com.cobbleraids.api;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable public view of the combat metadata an addon may use when selecting a raid boss. */
public record RaidBossDescriptor(
        ResourceLocation id,
        ResourceLocation species,
        String rarityTier,
        int level,
        long baseHealth,
        int maxPlayers,
        int timeLimitSeconds,
        boolean allowFlee
) {
    public RaidBossDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(rarityTier, "rarityTier");
        if (level < 1 || level > 100) throw new IllegalArgumentException("level must be 1..100");
        if (baseHealth < 1) throw new IllegalArgumentException("baseHealth must be >= 1");
        if (maxPlayers < 1 || maxPlayers > 4) throw new IllegalArgumentException("maxPlayers must be 1..4");
        if (timeLimitSeconds < 0) throw new IllegalArgumentException("timeLimitSeconds must be >= 0");
    }
}
