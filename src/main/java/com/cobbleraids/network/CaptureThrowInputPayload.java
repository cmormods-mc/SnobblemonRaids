package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player pressed for the throw. Same server-clock scoring discipline as
 * {@link CapturePulseInputPayload} -- no timing value travels with this packet, and the roll itself
 * (server-side, exactly once) is what this triggers.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CaptureThrowInputPayload(UUID raidId) implements CustomPacketPayload {
    public static final Type<CaptureThrowInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_throw_input_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureThrowInputPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CaptureThrowInputPayload::raidId,
            CaptureThrowInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
