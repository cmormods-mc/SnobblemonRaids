package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player either attempts the capture minigame or claims this raid's RP outright. raidId is
 * a defensive echo only -- the server always acts on the oldest unresolved session in its own queue,
 * never on this field's identity, the same contract {@link RewardChoicePayload} documents.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record CaptureChoicePayload(UUID raidId, boolean attempt) implements CustomPacketPayload {
    public static final Type<CaptureChoicePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_choice_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureChoicePayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CaptureChoicePayload::raidId,
            ByteBufCodecs.BOOL, CaptureChoicePayload::attempt,
            CaptureChoicePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
