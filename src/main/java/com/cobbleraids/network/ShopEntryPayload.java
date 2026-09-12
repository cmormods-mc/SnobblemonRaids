package com.cobbleraids.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * One cell of the shop, as the client needs to draw it. Not itself a packet.
 *
 * <p>Display data only, and deliberately so: the id is the only field the server will accept back.
 * The price travels because it has to be shown, not because it will be believed -- a purchase is
 * settled against the catalogue, so a client that edits this number pays the real one.
 *
 * <p>Written by hand rather than through StreamCodec.composite, which tops out at six fields.
 */
public record ShopEntryPayload(
        String id,
        int cost,
        boolean pokemon,
        ResourceLocation itemId,
        int count,
        String species,
        int level,
        boolean shiny,
        boolean owned
) {
    /** Stands in for the item field on a Pokemon entry, which has no item to name. */
    private static final ResourceLocation NONE = ResourceLocation.withDefaultNamespace("air");

    public static ShopEntryPayload item(String id, int cost, ResourceLocation itemId, int count) {
        return new ShopEntryPayload(id, cost, false, itemId, count, "", 0, false, false);
    }

    public static ShopEntryPayload pokemon(String id, int cost, String species, int level,
                                           boolean shiny, boolean owned) {
        return new ShopEntryPayload(id, cost, true, NONE, 1, species, level, shiny, owned);
    }

    public ShopEntryPayload withOwned(boolean value) {
        return new ShopEntryPayload(id, cost, pokemon, itemId, count, species, level, shiny, value);
    }

    public static final StreamCodec<ByteBuf, ShopEntryPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ShopEntryPayload decode(ByteBuf buffer) {
                    return new ShopEntryPayload(
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.BOOL.decode(buffer),
                            ResourceLocation.STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.BOOL.decode(buffer),
                            ByteBufCodecs.BOOL.decode(buffer));
                }

                @Override
                public void encode(ByteBuf buffer, ShopEntryPayload value) {
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.id());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.cost());
                    ByteBufCodecs.BOOL.encode(buffer, value.pokemon());
                    ResourceLocation.STREAM_CODEC.encode(buffer, value.itemId());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.count());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.species());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.level());
                    ByteBufCodecs.BOOL.encode(buffer, value.shiny());
                    ByteBufCodecs.BOOL.encode(buffer, value.owned());
                }
            };
}
