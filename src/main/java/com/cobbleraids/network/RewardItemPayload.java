package com.cobbleraids.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.resources.ResourceLocation;

/** One granted item line inside {@link RewardResultPayload}. Not itself a packet -- purely display data. */
public record RewardItemPayload(ResourceLocation item, int amount) {
}
