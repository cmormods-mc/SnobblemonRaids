package com.cobbleraids.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one page of a player's trophy room.
 *
 * <p>Sent fresh on every open and page turn rather than the client caching pages itself, the same
 * choice {@link ShopPagePayload} makes -- a trophy earned mid-session (another raid win while the
 * screen happens to be open) should show up on the next turn back to that page, not stay stale.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record TrophyRoomPagePayload(
        int pageIndex,
        int pageCount,
        List<TrophyRoomEntryPayload> entries
) implements CustomPacketPayload {
    public static final Type<TrophyRoomPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "trophy_room_page_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrophyRoomPagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::pageIndex,
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::pageCount,
            TrophyRoomEntryPayload.STREAM_CODEC.apply(ByteBufCodecs.list()), TrophyRoomPagePayload::entries,
            TrophyRoomPagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
