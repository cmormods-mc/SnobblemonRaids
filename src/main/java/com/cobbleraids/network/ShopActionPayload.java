package com.cobbleraids.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player asked for a page, or to buy something.
 *
 * <p>One payload for both because they carry the same two fields and the server validates both the
 * same way. {@code entryId} is empty for a page turn.
 *
 * <p>Everything here is a request, not an instruction. The page index is clamped and the entry id
 * is looked up in the catalogue; neither is trusted to be in range or to exist.
 *
 * <p>The channel carries a {@code _v1} suffix; see {@link RaidRewardPayloads} for why every payload
 * here does, and bump it if this record's fields ever change.
 */
public record ShopActionPayload(int pageIndex, String entryId) implements CustomPacketPayload {
    public static final Type<ShopActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "shop_action_v1"));

    /** Longer than any id the catalogue will accept, so a hostile client cannot send a novel. */
    public static final int MAX_ID_LENGTH = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShopActionPayload::pageIndex,
            ByteBufCodecs.stringUtf8(MAX_ID_LENGTH), ShopActionPayload::entryId,
            ShopActionPayload::new);

    public static ShopActionPayload turnTo(int pageIndex) {
        return new ShopActionPayload(pageIndex, "");
    }

    public static ShopActionPayload buy(int pageIndex, String entryId) {
        return new ShopActionPayload(pageIndex, entryId);
    }

    public boolean isPurchase() {
        return !entryId.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
