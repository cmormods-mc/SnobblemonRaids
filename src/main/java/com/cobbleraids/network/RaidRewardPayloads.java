package com.cobbleraids.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Registers CobbleRaids' custom payload types. Must run identically on both physical sides. */
public final class RaidRewardPayloads {
    private RaidRewardPayloads() {}

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(PendingRewardRevealPayload.TYPE, PendingRewardRevealPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RewardResultPayload.TYPE, RewardResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RewardChoicePayload.TYPE, RewardChoicePayload.STREAM_CODEC);
    }
}
