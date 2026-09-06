package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player picked this reward choice on the native screen. raidId is a defensive echo only --
 * the server always resolves the pending reward from its own queue, never from this field's identity.
 */
public record RewardChoicePayload(UUID raidId, String choiceId) implements CustomPacketPayload {
    public static final Type<RewardChoicePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "reward_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RewardChoicePayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RewardChoicePayload::raidId,
            ByteBufCodecs.STRING_UTF8, RewardChoicePayload::choiceId,
            RewardChoicePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
