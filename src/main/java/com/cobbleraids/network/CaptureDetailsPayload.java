package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: optional display metadata for the Raid Capture Protocol's offer/minigame screens -- the
 * boss's actual species (so the chamber can render its sprite instead of a placeholder), this
 * tier's rate figures (so the odds line reads real numbers instead of "server configured"), and the
 * sequence timeout (so the minigame screen's own countdown matches what the server will actually
 * enforce). Sent alongside {@link CaptureOfferPayload}, never in place of it: every existing input,
 * offer and result packet is unaffected if this one is dropped or never sent, and the screens fall
 * back to generic text rather than inventing a number.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CaptureDetailsPayload(
        UUID raidId,
        ResourceLocation species,
        double base,
        double stabilizationCap,
        double throwCap,
        int sequenceSeconds
) implements CustomPacketPayload {
    public static final Type<CaptureDetailsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_details_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureDetailsPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CaptureDetailsPayload::raidId,
            ResourceLocation.STREAM_CODEC, CaptureDetailsPayload::species,
            ByteBufCodecs.DOUBLE, CaptureDetailsPayload::base,
            ByteBufCodecs.DOUBLE, CaptureDetailsPayload::stabilizationCap,
            ByteBufCodecs.DOUBLE, CaptureDetailsPayload::throwCap,
            ByteBufCodecs.VAR_INT, CaptureDetailsPayload::sequenceSeconds,
            CaptureDetailsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
