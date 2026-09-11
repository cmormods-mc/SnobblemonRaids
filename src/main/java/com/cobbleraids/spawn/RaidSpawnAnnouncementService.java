package com.cobbleraids.spawn;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.RaidBroadcast;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Everything the wild-spawn system says to players: who hears it, and how precisely. */
final class RaidSpawnAnnouncementService {

    private RaidSpawnAnnouncementService() {}

    /**
     * Server-wide, because a wild raid is an invitation to everyone. Coordinates are deliberately
     * rounded: precise ones would make the announcement a waypoint and skip the finding entirely.
     */
    static void naturalSpawn(
            MinecraftServer server,
            PokemonEntity entity,
            ResourceLocation biomeId,
            ResourceLocation dimensionId,
            BlockPos position,
            RaidRarityTier tier
    ) {
        int hintX = coordinateHint(position.getX());
        int hintZ = coordinateHint(position.getZ());
        MutableComponent speciesName = entity.getPokemon().getSpecies().getTranslatedName();
        String biomeName = biomeId == null ? "Unknown Biome" : friendlyName(biomeId);

        MutableComponent message = Component.literal("[CobbleRaids] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("A wild ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(tier.displayName() + " ").withStyle(RaidTierPresentation.color(tier)))
                .append(speciesName.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" raid has appeared in ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(biomeName).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("! Coordinate hint: near X " + hintX + ", Z " + hintZ)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + friendlyName(dimensionId) + ").")
                        .withStyle(ChatFormatting.DARK_GRAY));

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            onlinePlayer.sendSystemMessage(message);
        }
    }

    /** A fading boss, told only to the players close enough to be looking at it. */
    static void expiryWarning(PokemonEntity boss, double radius, long secondsLeft) {
        RaidBroadcast.near(boss, radius, Component.literal("This raid boss will leave in ~" + secondsLeft + "s.")
                .withStyle(ChatFormatting.YELLOW));
    }

    static void bossLeft(PokemonEntity boss, double radius) {
        RaidBroadcast.near(boss, radius, Component.literal("The raid boss lost interest and left.")
                .withStyle(ChatFormatting.GRAY));
    }

    static int coordinateHint(int coordinate) {
        return coordinate >= 0 ? (coordinate + 50) / 100 * 100 : (coordinate - 50) / 100 * 100;
    }

    static String friendlyName(ResourceLocation id) {
        String[] words = id.getPath().replace('/', ' ').replace('_', ' ').split(" +");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.isEmpty() ? id.toString() : result.toString();
    }
}
