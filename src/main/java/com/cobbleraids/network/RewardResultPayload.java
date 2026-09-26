package com.cobbleraids.network;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the actual result of the player's claim -- what the server rolled and granted, or failure.
 *
 * <p>{@code currencyGranted} is a long rather than the BigInteger the economy API uses: the payout
 * policy caps a tier amount well inside a long, and the client only ever displays this number.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record RewardResultPayload(
        UUID raidId,
        boolean success,
        List<RewardItemPayload> granted,
        long currencyGranted,
        int raidPointsGranted,
        boolean hasMoreQueued
) implements CustomPacketPayload {
    public static final Type<RewardResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "reward_result_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RewardResultPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RewardResultPayload::raidId,
            ByteBufCodecs.BOOL, RewardResultPayload::success,
            RewardItemPayload.STREAM_CODEC.apply(ByteBufCodecs.list()), RewardResultPayload::granted,
            ByteBufCodecs.VAR_LONG, RewardResultPayload::currencyGranted,
            ByteBufCodecs.VAR_INT, RewardResultPayload::raidPointsGranted,
            ByteBufCodecs.BOOL, RewardResultPayload::hasMoreQueued,
            RewardResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
