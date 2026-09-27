package com.cobbleraids.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player turned a page in the trophy room. There is nothing to buy here, so unlike
 * {@link ShopActionPayload} this carries only the one field.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record TrophyRoomActionPayload(int pageIndex) implements CustomPacketPayload {
    public static final Type<TrophyRoomActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "trophy_room_action_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrophyRoomActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrophyRoomActionPayload::pageIndex,
            TrophyRoomActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
