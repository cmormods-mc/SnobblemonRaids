package com.cobbleraids.presentation;

import com.cobbleraids.config.RaidRarityTier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Single source of truth for how a rarity tier looks: chat color, ambient particle, styled name. */
public final class RaidTierPresentation {
    private RaidTierPresentation() {}

    public static ChatFormatting color(RaidRarityTier tier) {
        return switch (tier) {
            case STARTER -> ChatFormatting.GREEN;
            case POWERHOUSE -> ChatFormatting.BLUE;
            case LEGENDARY -> ChatFormatting.GOLD;
            case MYTHICAL -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    /** Ambient particle looped around a live boss; picked from vanilla types only, no extra resources. */
    public static ParticleOptions particle(RaidRarityTier tier) {
        return switch (tier) {
            case STARTER -> ParticleTypes.HAPPY_VILLAGER;
            case POWERHOUSE -> ParticleTypes.ELECTRIC_SPARK;
            case LEGENDARY -> ParticleTypes.END_ROD;
            case MYTHICAL -> ParticleTypes.PORTAL;
        };
    }

    public static MutableComponent styledName(RaidRarityTier tier, Component speciesName) {
        return Component.empty().append(speciesName.copy()).withStyle(color(tier));
    }
}
