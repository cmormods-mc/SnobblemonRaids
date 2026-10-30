package com.cobbleraids.radar.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional display-only S2C protocol. No client classes or player-authoritative raid actions. */
public record RadarHologramPayload(int mapId, int centerX, int centerZ, int baseY, int[] heights,
        boolean hasTarget, double targetX, double targetY, double targetZ,
        String species, String tier, long secondsLeft, boolean lastKnown) implements CustomPacketPayload {
    public static final int GRID = 33, SAMPLE_COUNT = GRID * GRID, UNKNOWN = Integer.MIN_VALUE;
    public static final Type<RadarHologramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("cobbleraids", "radar_hologram_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarHologramPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> payload.write(buffer), RadarHologramPayload::read);

    public RadarHologramPayload {
        if (heights.length != 0 && heights.length != SAMPLE_COUNT) throw new IllegalArgumentException("Expected zero or 1089 samples");
        for (int height : heights) if (height != UNKNOWN && (height <= Short.MIN_VALUE || height > Short.MAX_VALUE)) {
            throw new IllegalArgumentException("Height outside protocol bounds");
        }
        if (species.length() > 96 || tier.length() > 24) throw new IllegalArgumentException("Label too long");
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ)) {
            throw new IllegalArgumentException("Non-finite target");
        }
        heights = heights.clone();
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(mapId); buffer.writeInt(centerX); buffer.writeInt(centerZ); buffer.writeInt(baseY);
        buffer.writeBoolean(heights.length != 0);
        if (heights.length != 0) for (int height : heights) buffer.writeShort(height == UNKNOWN ? Short.MIN_VALUE : height);
        buffer.writeBoolean(hasTarget);
        if (hasTarget) { buffer.writeDouble(targetX); buffer.writeDouble(targetY); buffer.writeDouble(targetZ); }
        buffer.writeUtf(species, 96); buffer.writeUtf(tier, 24);
        buffer.writeVarLong(secondsLeft); buffer.writeBoolean(lastKnown);
    }
    private static RadarHologramPayload read(RegistryFriendlyByteBuf buffer) {
        int id = buffer.readInt(), x = buffer.readInt(), z = buffer.readInt(), y = buffer.readInt();
        int[] heights = new int[buffer.readBoolean() ? SAMPLE_COUNT : 0];
        for (int i = 0; i < heights.length; i++) {
            int height = buffer.readShort(); heights[i] = height == Short.MIN_VALUE ? UNKNOWN : height;
        }
        boolean target = buffer.readBoolean();
        double tx = target ? buffer.readDouble() : 0, ty = target ? buffer.readDouble() : 0, tz = target ? buffer.readDouble() : 0;
        return new RadarHologramPayload(id, x, z, y, heights, target, tx, ty, tz,
                buffer.readUtf(96), buffer.readUtf(24), buffer.readVarLong(), buffer.readBoolean());
    }
}
