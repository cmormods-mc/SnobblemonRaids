package com.cobbleraids.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: turn a page, or change the search/filter/sort the trophy room is browsing under. One payload
 * for both, the same reason {@link ShopActionPayload} is: opening the screen and every later request
 * carry the exact same fields, since a page turn is just a request that repeats the current filters.
 *
 * <p>Every field here is a request, not an instruction -- {@link com.cobbleraids.catching.
 * TrophyGalleryQuery#select} re-validates and clamps all of them, so a hostile or stale client can
 * only ever ask for something nonsensical, never receive it.
 *
 * <p>{@code shiny}/{@code sort} travel as raw ordinals rather than strings: both enums are this
 * mod's own and never leave the JVM's memory except over this one channel, so there is no format to
 * keep stable across a save file or a config an operator might hand-edit.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record TrophyRoomActionPayload(
        int pageIndex,
        int columns,
        String query,
        String tier,
        int shiny,
        int sort
) implements CustomPacketPayload {
    public static final Type<TrophyRoomActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "trophy_room_action_v1"));

    /** Longer than any search term the screen's own search box will let a player type. */
    public static final int MAX_QUERY_LENGTH = 64;
    public static final int MAX_TIER_LENGTH = 24;

    public static final StreamCodec<RegistryFriendlyByteBuf, TrophyRoomActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrophyRoomActionPayload::pageIndex,
            ByteBufCodecs.VAR_INT, TrophyRoomActionPayload::columns,
            ByteBufCodecs.stringUtf8(MAX_QUERY_LENGTH), TrophyRoomActionPayload::query,
            ByteBufCodecs.stringUtf8(MAX_TIER_LENGTH), TrophyRoomActionPayload::tier,
            ByteBufCodecs.VAR_INT, TrophyRoomActionPayload::shiny,
            ByteBufCodecs.VAR_INT, TrophyRoomActionPayload::sort,
            TrophyRoomActionPayload::new);

    /** What {@link com.cobbleraids.catching.TrophyRoomGateway#open} sends: page zero, no filters. */
    public static TrophyRoomActionPayload initial(int columns) {
        return new TrophyRoomActionPayload(0, columns, "", "", 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
