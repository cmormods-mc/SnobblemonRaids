package com.cobbleraids.network;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: a raid reward is waiting and the client's native reveal screen can render it.
 *
 * <p>The channel is {@code pending_reward_reveal_v3} because {@code renownBoon} changed the wire
 * format (v2 added {@code renownTitle} for the same reason). Keeping the old id would have sent
 * the longer packet to a client that still decodes the shorter one, and a custom payload with
 * bytes left over is a disconnect, not a warning. Under a new id an out-of-date client simply does
 * not advertise the channel, {@code canSend} is false, and the reward falls back to the chat claim
 * path it already supports. Change the id again the next time a field is added.
 */
public record PendingRewardRevealPayload(
        UUID raidId,
        ResourceLocation definitionId,
        String rarityTier,
        String speciesDisplayName,
        String renownTitle,
        String renownBoon,
        List<String> choiceIds,
        double contributionPercentage,
        int contributionBonusRolls,
        int elapsedCombatTicks,
        int participantCount
) {
}
