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
 */
public record ShopPagePayload(
        String heading,
        int pageIndex,
        int pageCount,
        int balance,
        List<ShopEntryPayload> entries
) implements CustomPacketPayload {
    public static final Type<ShopPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "shop_page"));

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
