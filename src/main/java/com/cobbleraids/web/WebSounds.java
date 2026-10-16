package com.cobbleraids.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Sounds for the web pages. A page cannot rely on playing audio itself, so the server plays the
 * sound for the one player, the same way the reveal's ball sound always was. {@link #later} holds a
 * sound back a few ticks, so a result can land after the page has finished its own animation.
 */
public final class WebSounds {
    private WebSounds() {}

    private record Due(long tick, UUID player, SoundEvent sound, float volume, float pitch) {}

    private static final List<Due> QUEUE = new ArrayList<>();

    public static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    public static void later(ServerPlayer player, int ticks, SoundEvent sound, float volume, float pitch) {
        QUEUE.add(new Due(player.server.getTickCount() + ticks, player.getUUID(), sound, volume, pitch));
    }

    /** Plays what has come due. A player who has left since is skipped. */
    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        long now = server.getTickCount();
        for (int i = QUEUE.size() - 1; i >= 0; i--) {
            Due due = QUEUE.get(i);
            if (due.tick() > now) continue;
            QUEUE.remove(i);
            ServerPlayer player = server.getPlayerList().getPlayer(due.player());
            if (player != null) play(player, due.sound(), due.volume(), due.pitch());
        }
    }

    public static void clear() {
        QUEUE.clear();
    }
}
