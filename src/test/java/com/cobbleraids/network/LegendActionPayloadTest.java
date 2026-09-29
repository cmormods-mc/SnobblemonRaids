package com.cobbleraids.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class LegendActionPayloadTest {

    @Test
    void actionRoundTrips() {
        LegendActionPayload input = new LegendActionPayload(2, 8, true, "legendary");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            LegendActionPayload.STREAM_CODEC.encode(buffer, input);
            assertEquals(input, LegendActionPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void initialIsPageZeroWithNoFilters() {
        LegendActionPayload initial = LegendActionPayload.initial(8);

        assertEquals(0, initial.pageIndex());
        assertEquals(8, initial.columns());
        assertEquals(false, initial.mineOnly());
        assertEquals("", initial.tier());
    }
}
