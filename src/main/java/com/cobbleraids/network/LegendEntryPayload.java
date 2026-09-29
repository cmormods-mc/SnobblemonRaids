package com.cobbleraids.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One row of the Hall of Legends, as the client needs to draw it. Not itself a packet.
 *
 * <p>Written by hand rather than through {@code StreamCodec.composite}, which tops out at six
 * fields; see {@link TrophyRoomEntryPayload}'s own header comment for the same reason.
 *
 * <p>{@code victorIds}/{@code victorNames} are bounded at {@code CobbleRaidsConfig.
 * VALIDATED_MAX_HUMAN_PLAYERS} (4) by construction on the server side -- see {@link LegendPagePayload}.
 */
public record LegendEntryPayload(
        String title,
        String species,
        String tier,
        String boon,
        long defeatedAtEpochMs,
        List<UUID> victorIds,
        List<String> victorNames
) {
    public static final StreamCodec<ByteBuf, LegendEntryPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public LegendEntryPayload decode(ByteBuf buffer) {
                    String title = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    String species = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    String tier = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    String boon = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    long defeatedAtEpochMs = ByteBufCodecs.VAR_LONG.decode(buffer);
                    int victorCount = ByteBufCodecs.VAR_INT.decode(buffer);
                    List<UUID> victorIds = new ArrayList<>(victorCount);
                    for (int i = 0; i < victorCount; i++) {
                        victorIds.add(new UUID(buffer.readLong(), buffer.readLong()));
                    }
                    List<String> victorNames = new ArrayList<>(victorCount);
                    for (int i = 0; i < victorCount; i++) {
                        victorNames.add(ByteBufCodecs.STRING_UTF8.decode(buffer));
                    }
                    return new LegendEntryPayload(title, species, tier, boon, defeatedAtEpochMs,
                            victorIds, victorNames);
                }

                @Override
                public void encode(ByteBuf buffer, LegendEntryPayload value) {
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.title());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.species());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.tier());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, value.boon());
                    ByteBufCodecs.VAR_LONG.encode(buffer, value.defeatedAtEpochMs());
                    ByteBufCodecs.VAR_INT.encode(buffer, value.victorIds().size());
                    for (UUID id : value.victorIds()) {
                        buffer.writeLong(id.getMostSignificantBits());
                        buffer.writeLong(id.getLeastSignificantBits());
                    }
                    for (String name : value.victorNames()) {
                        ByteBufCodecs.STRING_UTF8.encode(buffer, name);
                    }
                }
            };
}
