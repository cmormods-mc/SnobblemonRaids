package com.cobbleraids.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureDetailsPayloadTest {

    @Test
    @DisplayName("display metadata round-trips without changing the existing offer protocol's channel id")
    void displayMetadataRoundTripsWithoutChangingExistingOfferProtocol() {
        CaptureDetailsPayload input = new CaptureDetailsPayload(
                UUID.randomUUID(), ResourceLocation.parse("cobblemon:mewtwo"), 0.04, 0.015, 0.015, 18);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            CaptureDetailsPayload.STREAM_CODEC.encode(buffer, input);

            assertEquals(input, CaptureDetailsPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            assertEquals("capture_offer_v1", CaptureOfferPayload.TYPE.id().getPath());
        } finally {
            buffer.release();
        }
    }
}
