package com.cobbleraids.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one page of the shop, plus the balance to price it against.
 *
 * <p>Sent on open and again after every purchase, rather than the client adjusting its own copy.
 * The balance and the owned flags are the server's answer, and a screen that edited them locally
 * would show a player a purchase that had not happened.
 *
 * <p>{@code balance} is an {@code int} because {@link com.cobbleraids.reward.points.RaidPointsStore}
 * is: Raid Points are stored and clamped as an {@code int} at the source
 * ({@code RaidPlayerRecord.withPoints}), so there is nothing wider here to lose. This is a
 * different currency from {@link RewardResultPayload#currencyGranted}'s {@code long} -- that one is
 * an optional economy-mod grant amount, not a Raid Points balance -- so the two are not actually the
 * same value under two types.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record ShopPagePayload(
        String heading,
        int pageIndex,
        int pageCount,
        int balance,
        List<ShopEntryPayload> entries
) implements CustomPacketPayload {
    public static final Type<ShopPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "shop_page_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ShopPagePayload::heading,
            ByteBufCodecs.VAR_INT, ShopPagePayload::pageIndex,
            ByteBufCodecs.VAR_INT, ShopPagePayload::pageCount,
            ByteBufCodecs.VAR_INT, ShopPagePayload::balance,
            ShopEntryPayload.STREAM_CODEC.apply(ByteBufCodecs.list()), ShopPagePayload::entries,
            ShopPagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
