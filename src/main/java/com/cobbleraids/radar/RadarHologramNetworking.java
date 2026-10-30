package com.cobbleraids.radar;

import com.cobbleraids.radar.protocol.RadarHologramPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Never sends an unknown payload to a client without the optional receiver. */
public final class RadarHologramNetworking {
    private RadarHologramNetworking() {}
    public static void register() {
        PayloadTypeRegistry.playS2C().register(RadarHologramPayload.TYPE, RadarHologramPayload.CODEC);
    }
    public static boolean canShow(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, RadarHologramPayload.TYPE);
    }
    public static void send(ServerPlayer player, RadarHologramPayload payload) {
        if (canShow(player)) ServerPlayNetworking.send(player, payload);
    }
}
