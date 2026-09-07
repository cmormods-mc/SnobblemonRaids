package com.cobbleraids.network;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C: a raid reward is waiting and the client's native reveal screen can render it. */
public record PendingRewardRevealPayload(
        UUID raidId,
        ResourceLocation definitionId,
        String rarityTier,
        String speciesDisplayName,
        List<String> choiceIds,
        double contributionPercentage,
        int contributionBonusRolls,
        int elapsedCombatTicks,
        int participantCount
) implements CustomPacketPayload {
    public static final Type<PendingRewardRevealPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cobbleraids", "pending_reward_reveal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PendingRewardRevealPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, payload.raidId());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.definitionId());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.rarityTier());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.speciesDisplayName());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, payload.choiceIds());
                ByteBufCodecs.DOUBLE.encode(buf, payload.contributionPercentage());
                ByteBufCodecs.VAR_INT.encode(buf, payload.contributionBonusRolls());
                ByteBufCodecs.VAR_INT.encode(buf, payload.elapsedCombatTicks());
                ByteBufCodecs.VAR_INT.encode(buf, payload.participantCount());
            },
            buf -> new PendingRewardRevealPayload(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
