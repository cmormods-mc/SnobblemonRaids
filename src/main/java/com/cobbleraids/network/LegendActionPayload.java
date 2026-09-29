package com.cobbleraids.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: turn a page, or change the "mine"/tier filter the Hall of Legends is browsing under. One
 * payload for both, same reasoning as {@link TrophyRoomActionPayload}: opening the screen and every
 * later request carry the exact same fields.
 *
 * <p>Every field here is a request, not an instruction -- {@link com.cobbleraids.catching.
 * LegendGalleryQuery#select} re-validates and clamps all of them.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record LegendActionPayload(
        int pageIndex,
        int columns,
        boolean mineOnly,
        String tier
) implements CustomPacketPayload {
    public static final Type<LegendActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "legend_room_action_v1"));

    public static final int MAX_TIER_LENGTH = 24;

    public static final StreamCodec<RegistryFriendlyByteBuf, LegendActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LegendActionPayload::pageIndex,
            ByteBufCodecs.VAR_INT, LegendActionPayload::columns,
            ByteBufCodecs.BOOL, LegendActionPayload::mineOnly,
            ByteBufCodecs.stringUtf8(MAX_TIER_LENGTH), LegendActionPayload::tier,
            LegendActionPayload::new);

    /** What {@link com.cobbleraids.catching.LegendRoomGateway#open} sends: page zero, no filters. */
    public static LegendActionPayload initial(int columns) {
        return new LegendActionPayload(0, columns, false, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
