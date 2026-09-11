package com.cobbleraids.presentation;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Telling the players around a raid boss something. */
public final class RaidBroadcast {

    private RaidBroadcast() {}

    /**
     * Sends {@code message} to every player within {@code radius} of the boss.
     *
     * <p>Walks the boss's own level rather than the whole server: only players in this dimension can
     * possibly be in range, and on a busy server that is a far shorter list. Was written out twice,
     * once for lobby countdowns and once for spawn and despawn notices.
     */
    public static void near(PokemonEntity boss, double radius, Component message) {
        if (!(boss.level() instanceof ServerLevel level)) return;
        double radiusSqr = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(boss) <= radiusSqr) player.sendSystemMessage(message);
        }
    }
}
