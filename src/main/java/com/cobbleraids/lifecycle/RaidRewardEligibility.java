package com.cobbleraids.lifecycle;

import com.cobbleraids.raid.RaidSession;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable terminal snapshot used by the reward layer; independent from actor-level event ordering.
 *
 * <p>{@code renownTitle} is empty for an ordinary boss. It rides along because nothing downstream
 * can ask the boss any more: the entity is gone by the time a reward is claimed.
 */
public record RaidRewardEligibility(
        UUID raidId,
        ResourceLocation definitionId,
        RaidOutcome outcome,
        Map<UUID, Float> contribution,
        Set<UUID> participants,
        int elapsedCombatTicks,
        String renownTitle) {
    public RaidRewardEligibility {
        renownTitle = renownTitle == null ? "" : renownTitle;
    }

    public static RaidRewardEligibility victory(RaidSession raid) {
        return new RaidRewardEligibility(
                raid.getId(), raid.getDefinitionId(), RaidOutcome.VICTORY,
                raid.getContributionSnapshot(), raid.getActiveParticipants(), raid.getElapsedCombatTicks(),
                raid.getRenownTitle());
    }
}
