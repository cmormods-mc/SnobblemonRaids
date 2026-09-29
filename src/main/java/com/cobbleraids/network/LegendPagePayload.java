package com.cobbleraids.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one page of the server's Hall of Legends, under whatever "mine"/tier filter it was requested
 * with. Sent fresh on every open, page turn and filter change, same reasoning as
 * {@link TrophyRoomPagePayload}: a legend recorded mid-session should show up on the next turn back
 * to that page, not stay stale.
 *
 * <p>{@code total}/{@code mineCount} are counts after the tier filter but before "mine", so the
 * header can say "3 of yours, 40 total" while browsing everyone's.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record LegendPagePayload(
        int pageIndex,
        int pageCount,
        int total,
        int mineCount,
        List<LegendEntryPayload> entries
) implements CustomPacketPayload {
    public static final Type<LegendPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "legend_room_page_v1"));

    private static final int MAX_ENTRIES = com.cobbleraids.catching.LegendGalleryQuery.MAX_COLUMNS;

    public static final StreamCodec<RegistryFriendlyByteBuf, LegendPagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LegendPagePayload::pageIndex,
            ByteBufCodecs.VAR_INT, LegendPagePayload::pageCount,
            ByteBufCodecs.VAR_INT, LegendPagePayload::total,
            ByteBufCodecs.VAR_INT, LegendPagePayload::mineCount,
            LegendEntryPayload.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), LegendPagePayload::entries,
            LegendPagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
