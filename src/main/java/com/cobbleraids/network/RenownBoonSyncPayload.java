package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: tells a client which {@link com.cobbleraids.renown.RenownBoon} a renowned raid boss's
 * Pokemon (identified by its own UUID, not the Minecraft entity's) actually has.
 *
 * <p>Exists because there is no way to carry this on the boss's own display name: Cobblemon's
 * {@code PokemonEntity.setCustomName} flattens whatever {@code Component} it is given down to a
 * bare string (via {@code getString()}) before storing it, discarding every {@code Style} --
 * colours and, load-bearing here, a would-be marker riding on it. See
 * {@link com.cobbleraids.client.renown.RenownBoonClientCache} for the client-side cache this
 * payload fills, and {@link com.cobbleraids.presentation.RenownBoonSyncService} for when it is
 * sent (on spawn, and resent periodically so a player who starts tracking the boss later -- moved
 * into range, reconnected, joined late -- still gets it without a dedicated tracking-start event).
 */
public record RenownBoonSyncPayload(UUID pokemonUuid, String boonEncoded) implements CustomPacketPayload {
    public static final Type<RenownBoonSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "renown_boon_sync_v1"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RenownBoonSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, payload.pokemonUuid());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.boonEncoded());
            },
            buf -> new RenownBoonSyncPayload(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
