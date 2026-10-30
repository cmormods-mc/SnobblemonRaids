package com.cobbleraids.radar;
import static org.junit.jupiter.api.Assertions.*;
import com.cobbleraids.radar.protocol.RadarHologramPayload;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
class RadarHologramPayloadTest {
    private RadarHologramPayload payload(int[] heights,boolean target) {
        return new RadarHologramPayload(27,-128,64,70,heights,target,120,94,-160,"charizard","STARTER",97,true);
    }
    @Test void fullTerrainRoundTripsWithinBoundedPacket() {
        int[] heights=new int[33*33];Arrays.fill(heights,64);heights[17]=RadarHologramPayload.UNKNOWN;heights[18]=-64;
        var source=payload(heights,true);heights[19]=300;
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            RadarHologramPayload.CODEC.encode(buffer,source);
            assertTrue(buffer.readableBytes()<2400);
            var decoded=RadarHologramPayload.CODEC.decode(buffer);
            assertArrayEquals(source.heights(),decoded.heights());
            assertEquals(64,decoded.heights()[19]);assertEquals(27,decoded.mapId());
            assertEquals(-128,decoded.centerX());assertEquals(120,decoded.targetX());
            assertEquals("charizard",decoded.species());assertEquals(97,decoded.secondsLeft());
            assertEquals(0,buffer.readableBytes());
        } finally {buffer.release();}
    }
    @Test void poseUpdatesOmitTerrain() {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            RadarHologramPayload.CODEC.encode(buffer,payload(new int[0],false));
            assertTrue(buffer.readableBytes()<100);
            var decoded=RadarHologramPayload.CODEC.decode(buffer);
            assertEquals(0,decoded.heights().length);assertFalse(decoded.hasTarget());
        } finally {buffer.release();}
    }
    @Test void malformedSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->payload(new int[5],true));
        int[] heights=new int[33*33];heights[0]=Short.MIN_VALUE;
        assertThrows(IllegalArgumentException.class,()->payload(heights,true));
    }
}
