package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player pressed for one stabilization pulse. Carries no timing value -- the server scores
 * the pulse from when it itself receives this packet relative to when that pulse's travel window
 * began, never from anything the client reports, so there is nothing here for a hostile client to lie
 * about beyond which pulse it claims to be (rejected as a no-op if it isn't the session's next one).
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CapturePulseInputPayload(UUID raidId, int pulseIndex) implements CustomPacketPayload {
    public static final Type<CapturePulseInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_pulse_input_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapturePulseInputPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CapturePulseInputPayload::raidId,
            ByteBufCodecs.VAR_INT, CapturePulseInputPayload::pulseIndex,
            CapturePulseInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
