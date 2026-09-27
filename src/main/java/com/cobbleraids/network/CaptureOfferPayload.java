package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: a Raid Capture Protocol choice is waiting -- attempt the minigame, or claim this raid's RP.
 * Sent only after the player has actually claimed this raid's reward (items already in hand,
 * unaffected); {@code raidPointsAtStake} is therefore the exact amount that specific claim computed
 * (renown and held-item multipliers already applied), not an estimate -- it is what capturing
 * forgoes, in full.
 *
 * <p>The six timing fields are this tier's {@code CaptureTierConfig} travel/zone widths, synced here
 * because the client has no other way to know them -- server config is never implicitly visible to
 * the client, and the minigame screen needs them to animate an accurate sweep and draw correctly
 * sized zones, not just to send inputs blind. The server alone still scores every input off its own
 * copy of these same numbers; nothing the client renders is trusted back.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CaptureOfferPayload(
        UUID raidId,
        ResourceLocation definitionId,
        String tier,
        String speciesDisplayName,
        boolean shiny,
        int raidPointsAtStake,
        int choiceSecondsRemaining,
        int pulseTravelDurationMs,
        int pulseGoodZoneWidthMs,
        int pulsePerfectZoneWidthMs,
        int throwTravelDurationMs,
        int throwGoodZoneWidthMs,
        int throwPerfectZoneWidthMs
) implements CustomPacketPayload {
    public static final Type<CaptureOfferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_offer_v1"));

    // More fields than StreamCodec.composite supports (its widest overload takes six), so this is
    // spelled out by hand the same way PendingRewardRevealPayload's own eleven fields are.
    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureOfferPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, payload.raidId());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.definitionId());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.tier());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.speciesDisplayName());
                ByteBufCodecs.BOOL.encode(buf, payload.shiny());
                ByteBufCodecs.VAR_INT.encode(buf, payload.raidPointsAtStake());
                ByteBufCodecs.VAR_INT.encode(buf, payload.choiceSecondsRemaining());
                ByteBufCodecs.VAR_INT.encode(buf, payload.pulseTravelDurationMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.pulseGoodZoneWidthMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.pulsePerfectZoneWidthMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.throwTravelDurationMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.throwGoodZoneWidthMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.throwPerfectZoneWidthMs());
            },
            buf -> new CaptureOfferPayload(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
