package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the one and only capture roll, already decided and already persisted server-side before this
 * is ever sent. The client only ever animates toward this already-known result -- it never requests
 * an outcome after a local-only animation.
 *
 * <p>{@code delivery} mirrors {@code RaidCaptureSession.DeliveryState}'s name ({@code DELIVERED},
 * {@code PENDING_RETRY}, or {@code NONE} on the RP route, where nothing was ever rolled to deliver)
 * as a plain string rather than sharing the enum type, the same reason
 * {@link com.cobbleraids.catching.TrophyGalleryQuery}'s sort/tier fields travel as raw values instead
 * of a shared type: this channel and that server-side enum are free to change independently.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CaptureResultPayload(
        UUID raidId,
        boolean success,
        double finalChancePercent,
        double stabilizationQualityPercent,
        double throwQualityPercent,
        String delivery,
        String speciesDisplayName,
        boolean shiny
) implements CustomPacketPayload {
    public static final Type<CaptureResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_result_v1"));

    // More fields than StreamCodec.composite supports (its widest overload takes six), so this is
    // spelled out by hand the same way PendingRewardRevealPayload's own eleven fields are.
    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, payload.raidId());
                ByteBufCodecs.BOOL.encode(buf, payload.success());
                ByteBufCodecs.DOUBLE.encode(buf, payload.finalChancePercent());
                ByteBufCodecs.DOUBLE.encode(buf, payload.stabilizationQualityPercent());
                ByteBufCodecs.DOUBLE.encode(buf, payload.throwQualityPercent());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.delivery());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.speciesDisplayName());
                ByteBufCodecs.BOOL.encode(buf, payload.shiny());
            },
            buf -> new CaptureResultPayload(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
