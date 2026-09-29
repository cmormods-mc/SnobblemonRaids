package com.cobbleraids.catching;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells the server when a genuine Hall of Legends first is set. Its own mute tag, separate from
 * {@code RaidSpawnAnnouncementService}'s -- a player may want wild-spawn pings without hall-of-fame
 * chatter, or vice versa, so the two opt-outs are independent.
 */
public final class HallOfLegendsAnnouncementService {

    private static final String MUTE_TAG = "cobbleraids_mute_legend_announcements";

    private HallOfLegendsAnnouncementService() {}

    /** Whether this player has opted out of {@link #announce}'s server-wide message. */
    public static boolean isMuted(ServerPlayer player) {
        return player.getTags().contains(MUTE_TAG);
    }

    /** Flips the mute tag and reports the new state. Called from {@code /cobbleraids notify legends}. */
    public static boolean toggleMute(ServerPlayer player) {
        boolean muted = !isMuted(player);
        if (muted) player.addTag(MUTE_TAG); else player.removeTag(MUTE_TAG);
        return muted;
    }

    /**
     * Server-wide by default, same reasoning as a wild raid spawn: a first-ever record is rare and
     * meant to be seen. Called only by {@link HallOfLegends#recordFirstDefeat}'s caller, and only when
     * that call actually claimed the record -- a repeat of the same (title, species) pairing never
     * re-announces.
     */
    public static void announce(MinecraftServer server, LegendEntry entry) {
        MutableComponent message = Component.literal("[CobbleRaids] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("A new legend is born! ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(creditLine(entry.victorNames())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" have defeated ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(entry.title()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" the ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(entry.species().getPath()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" for the first time on this server!").withStyle(ChatFormatting.YELLOW));

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (!isMuted(onlinePlayer)) onlinePlayer.sendSystemMessage(message);
        }
    }

    /** "CMor" for one victor, "CMor and 2 others" for more -- never lists every name past the first. */
    private static String creditLine(List<String> victorNames) {
        if (victorNames.isEmpty()) return "Someone";
        if (victorNames.size() == 1) return victorNames.get(0);
        int others = victorNames.size() - 1;
        return victorNames.get(0) + " and " + others + " other" + (others == 1 ? "" : "s");
    }
}
