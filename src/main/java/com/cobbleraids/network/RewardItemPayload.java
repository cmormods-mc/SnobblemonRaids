package com.cobbleraids.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** One granted item line inside {@link RewardResultPayload}. Not itself a packet -- purely display data. */
public record RewardItemPayload(ResourceLocation item, int amount) {
    public static final StreamCodec<ByteBuf, RewardItemPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, RewardItemPayload::item,
            ByteBufCodecs.VAR_INT, RewardItemPayload::amount,
            RewardItemPayload::new);
}
