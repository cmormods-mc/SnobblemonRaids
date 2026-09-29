package com.cobbleraids.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class LegendPagePayloadTest {

    @Test
    void pageRoundTrips() {
        LegendEntryPayload row = new LegendEntryPayload("Kaelen, the Relentless", "gyarados",
                "legendary", "hp_pool", 1_000_000L, List.of(UUID.randomUUID()), List.of("Alice"));
        LegendPagePayload input = new LegendPagePayload(1, 3, 40, 5, List.of(row));

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            LegendPagePayload.STREAM_CODEC.encode(buffer, input);
            assertEquals(input, LegendPagePayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            assertEquals("legend_room_page_v1", LegendPagePayload.TYPE.id().getPath());
        } finally {
            buffer.release();
        }
    }
}
