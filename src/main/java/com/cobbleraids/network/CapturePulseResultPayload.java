package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one stabilization pulse has been judged. Sent the instant the server scores it -- not batched
 * with the other two -- so the client can show feedback (Unstable/Stable/Perfect) as each pulse lands
 * rather than waiting for the whole sequence.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CapturePulseResultPayload(UUID raidId, int pulseIndex, double score) implements CustomPacketPayload {
    public static final Type<CapturePulseResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_pulse_result_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapturePulseResultPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CapturePulseResultPayload::raidId,
            ByteBufCodecs.VAR_INT, CapturePulseResultPayload::pulseIndex,
            ByteBufCodecs.DOUBLE, CapturePulseResultPayload::score,
            CapturePulseResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
