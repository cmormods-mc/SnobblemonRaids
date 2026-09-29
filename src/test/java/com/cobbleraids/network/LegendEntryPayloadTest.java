package com.cobbleraids.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegendEntryPayloadTest {

    private static LegendEntryPayload roundTrip(LegendEntryPayload original) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            LegendEntryPayload.STREAM_CODEC.encode(buffer, original);
            LegendEntryPayload result = LegendEntryPayload.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
            return result;
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("a full-party (4 victor) entry round-trips with names still paired to the right id")
    void fullPartyEntryRoundTrips() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<String> names = List.of("Alice", "Bob", "Cara", "Dee");
        LegendEntryPayload original = new LegendEntryPayload("Kaelen, the Relentless", "gyarados",
                "legendary", "stat_focus:attack", 1_000_000L, ids, names);

        LegendEntryPayload result = roundTrip(original);

        assertEquals(original, result);
        assertEquals(ids, result.victorIds());
        assertEquals(names, result.victorNames());
    }

    @Test
    @DisplayName("a single-victor (solo) entry round-trips")
    void soloEntryRoundTrips() {
        LegendEntryPayload original = new LegendEntryPayload("Doraan, the Ancient", "garchomp",
                "starter", "hp_pool", 42L, List.of(UUID.randomUUID()), List.of("Solo"));

        assertEquals(original, roundTrip(original));
    }
}
