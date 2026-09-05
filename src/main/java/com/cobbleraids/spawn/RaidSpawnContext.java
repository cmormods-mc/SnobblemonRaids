package com.cobbleraids.spawn;

import com.cobbleraids.config.RaidDefinition;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** Immutable environmental snapshot used to decide which raid definitions are eligible at a candidate point. */
public record RaidSpawnContext(
        ResourceLocation dimension,
        ResourceLocation biome,
        Holder<Biome> biomeHolder,
        long dayTime
) {
    public boolean matches(RaidDefinition definition) {
        RaidDefinition.Spawn spawn = definition.spawn();
        if (!spawn.enabled()) return false;
        if (!spawn.allowsDimension(dimension)) return false;
        if (!spawn.allowsTime(dayTime)) return false;

        boolean hasBiomeRestriction = !spawn.biomes().isEmpty() || !spawn.biomeTags().isEmpty();
        if (!hasBiomeRestriction) return true;
        if (biome != null && spawn.biomes().contains(biome)) return true;
        for (ResourceLocation tagId : spawn.biomeTags()) {
            if (biomeHolder.is(TagKey.create(Registries.BIOME, tagId))) return true;
        }
        return false;
    }
}
