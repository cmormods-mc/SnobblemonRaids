package com.cobbleraids.lifecycle;

import com.cobbleraids.raid.RaidSession;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable terminal snapshot used by the reward layer; independent from actor-level event ordering. */
public record RaidRewardEligibility(
        UUID raidId,
        ResourceLocation definitionId,
        RaidOutcome outcome,
        Map<UUID, Float> contribution,
        Set<UUID> participants) {
    public static RaidRewardEligibility victory(RaidSession raid) {
        return new RaidRewardEligibility(
                raid.getId(), raid.getDefinitionId(), RaidOutcome.VICTORY,
                raid.getContributionSnapshot(), raid.getActiveParticipants());
    }
}
