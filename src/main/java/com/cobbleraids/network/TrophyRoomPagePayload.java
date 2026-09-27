package com.cobbleraids.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one page of a player's trophy room, under whatever search/filter/sort it was requested with.
 *
 * <p>Sent fresh on every open, page turn and filter change rather than the client caching pages
 * itself, the same choice {@link ShopPagePayload} makes -- a trophy earned mid-session should show up
 * on the next turn back to that page, not stay stale.
 *
 * <p>{@code total}/{@code shinyTotal} are unfiltered counts across the player's whole trophy room, so
 * the header can say "4 of 130 trophies" even while a search narrows what {@code entries} holds.
 * {@code filteredCount} is how many trophies matched the current search/filter, which is what
 * pagination is computed against.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record TrophyRoomPagePayload(
        int pageIndex,
        int pageCount,
        int total,
        int shinyTotal,
        int filteredCount,
        List<TrophyRoomEntryPayload> entries
) implements CustomPacketPayload {
    public static final Type<TrophyRoomPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "trophy_room_page_v1"));

    /** {@link com.cobbleraids.catching.TrophyGalleryQuery#MAX_COLUMNS} times two, for the preview strip. */
    private static final int MAX_ENTRIES = com.cobbleraids.catching.TrophyGalleryQuery.MAX_COLUMNS * 2;

    public static final StreamCodec<RegistryFriendlyByteBuf, TrophyRoomPagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::pageIndex,
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::pageCount,
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::total,
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::shinyTotal,
            ByteBufCodecs.VAR_INT, TrophyRoomPagePayload::filteredCount,
            TrophyRoomEntryPayload.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), TrophyRoomPagePayload::entries,
            TrophyRoomPagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
