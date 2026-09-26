package com.cobbleraids.spawn;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.presentation.RaidBroadcast;
import com.cobbleraids.renown.RaidRenown;
import com.cobbleraids.renown.RaidRenownMarker;
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
public final class RaidSpawnAnnouncementService {

    /**
     * A player tag rather than a separate store: it needs to persist with the player and nothing
     * else, and a tag already does that with no save file and no cleanup hook, the same reasoning
     * {@code RaidBossEntityMarker}/{@code RaidRenownMarker} use it for on a boss.
     */
    private static final String MUTE_TAG = "cobbleraids_mute_wild_spawn_announcements";

    private RaidSpawnAnnouncementService() {}

    /** Whether this player has opted out of {@link #naturalSpawn}'s server-wide announcement. */
    public static boolean isMuted(ServerPlayer player) {
        return player.getTags().contains(MUTE_TAG);
    }

    /** Flips the mute tag and reports the new state. Called from {@code /cobbleraids notify}. */
    public static boolean toggleMute(ServerPlayer player) {
        boolean muted = !isMuted(player);
        if (muted) player.addTag(MUTE_TAG); else player.removeTag(MUTE_TAG);
        return muted;
    }

    /**
     * Server-wide by default, because a wild raid is an invitation to everyone -- see
     * {@link #toggleMute} for the opt-out. Coordinates are deliberately rounded: precise ones would
     * make the announcement a waypoint and skip the finding entirely.
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
        RaidRenown renown = RaidRenownMarker.read(entity).orElse(null);

        MutableComponent message = Component.literal("[CobbleRaids] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (renown == null) {
            message.append(Component.literal("A wild ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(tier.displayName() + " ").withStyle(RaidTierPresentation.color(tier)))
                    .append(speciesName.copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" raid has appeared in ").withStyle(ChatFormatting.YELLOW));
        } else {
            // Led by the title, in the same styling as the boss's nameplate, so the name a player
            // reads in chat is the one they find floating over the boss.
            message.append(RaidBossNameplate.of(tier, speciesName, renown, 0))
                    .append(Component.literal(", a renowned ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(tier.displayName()).withStyle(RaidTierPresentation.color(tier)))
                    .append(Component.literal(" raid, has appeared in ").withStyle(ChatFormatting.YELLOW));
        }
        message.append(Component.literal(biomeName).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("! Coordinate hint: near X " + hintX + ", Z " + hintZ)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + friendlyName(dimensionId) + ").")
                        .withStyle(ChatFormatting.DARK_GRAY));

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (!isMuted(onlinePlayer)) onlinePlayer.sendSystemMessage(message);
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
