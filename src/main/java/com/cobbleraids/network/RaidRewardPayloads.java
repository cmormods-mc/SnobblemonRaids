package com.cobbleraids.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers CobbleRaids' custom payload types. Must run identically on both physical sides.
 *
 * <p>Every channel id below carries a {@code _v1} (or higher) suffix, established by
 * {@link PendingRewardRevealPayload} and now applied uniformly: bump the suffix whenever a
 * payload's wire format changes, rather than editing the codec under the same id. A byte-count
 * mismatch on a fixed channel is a disconnect, not a warning; under a new id, a client running an
 * older build of this mod simply does not advertise the channel, {@code canSend} is false, and the
 * caller falls back to whatever non-packet path it already has (chat, in this mod's case) instead
 * of getting kicked.
 */
public final class RaidRewardPayloads {
    private RaidRewardPayloads() {}

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(PendingRewardRevealPayload.TYPE, PendingRewardRevealPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RewardResultPayload.TYPE, RewardResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RewardChoicePayload.TYPE, RewardChoicePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShopPagePayload.TYPE, ShopPagePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ShopActionPayload.TYPE, ShopActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RenownBoonSyncPayload.TYPE, RenownBoonSyncPayload.STREAM_CODEC);
    }
}
