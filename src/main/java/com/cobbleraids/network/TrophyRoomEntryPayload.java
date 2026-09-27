package com.cobbleraids.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One cell of the trophy room, as the client needs to draw it. Not itself a packet.
 *
 * <p>Written by hand rather than through {@code StreamCodec.composite}, which tops out at six
 * fields; see {@link ShopEntryPayload}'s own header comment for the same reason.
 */
public record TrophyRoomEntryPayload(
        String species,
        int level,
        boolean shiny,
        int ivPercent,
        int evPercent,
        String tier,
        long firstDefeatedAtEpochMs,
        int timesDefeated
) {
    public static final StreamCodec<ByteBuf, TrophyRoomEntryPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TrophyRoomEntryPayload decode(ByteBuf buffer) {
                    return new TrophyRoomEntryPayload(
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.BOOL.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.VAR_LONG.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer));
                }

                @Override
                public void encode(ByteBuf buffer, TrophyRoomEntryPayload value) {
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.species());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.level());
                    ByteBufCodecs.BOOL.encode(buffer, value.shiny());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.ivPercent());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.evPercent());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.tier());
                    ByteBufCodecs.VAR_LONG.encode(buffer, value.firstDefeatedAtEpochMs());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.timesDefeated());
                }
            };
}
