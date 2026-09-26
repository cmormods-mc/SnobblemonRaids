package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * One player's saved copy of a boss they helped defeat, ready to buy back from the personal shop.
 *
 * <p>{@code pokemonNbt} is the source of truth -- Cobblemon's own {@code Pokemon.saveToNBT} output,
 * carrying everything about the real, already-rolled individual (IVs, EVs, nature, ability, gender,
 * shininess, form, tera type, held item, level, moveset). It is deserialized only at the moment of
 * an actual purchase or reroll, never while just building a shop page: {@code ivPercent}/
 * {@code evPercent}/{@code species}/{@code level}/{@code shiny} are precomputed once, at capture or
 * reroll time, specifically so a page build never has to touch the blob.
 */
public record BossSnapshot(
        ResourceLocation species,
        int level,
        boolean shiny,
        int ivPercent,
        int evPercent,
        RaidRarityTier rarityTier,
        CompoundTag pokemonNbt,
        long capturedAtEpochMs
) {
}
