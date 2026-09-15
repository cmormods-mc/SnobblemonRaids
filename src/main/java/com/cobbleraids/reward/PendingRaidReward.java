package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidRarityTier;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable per-player claim token. The full reward config is snapshotted so /reload cannot rewrite
 * an earned reward.
 *
 * <p>{@code rewardSeed} is fixed when the raid is won, not when the claim is made, and every roll a
 * claim performs derives from it. That is what makes an unclaimed reward survive a restart as the
 * same reward: the contents are regenerated rather than stored, so nothing has to serialise an
 * ItemStack, and logging off does not reroll what you already earned. It is only as stable as the
 * loot tables behind it -- edit a table between the win and the claim and the same seed yields
 * something else, which is the trade this makes against freezing the stacks outright.
 *
 * <p>{@code renownTitle} is "Kaelen, the Relentless" for a renowned boss and empty otherwise. It
 * is both what the reward screen shows and what makes the claim pay renown's multipliers.
 */
public record PendingRaidReward(
        UUID raidId,
        ResourceLocation definitionId,
        RaidRarityTier rarityTier,
        RaidDefinition.Rewards rewards,
        double contributionPercentage,
        int contributionBonusRolls,
        int elapsedCombatTicks,
        int participantCount,
        long rewardSeed,
        String renownTitle
) {
    public PendingRaidReward {
        renownTitle = renownTitle == null ? "" : renownTitle;
    }

    public boolean renowned() {
        return !renownTitle.isEmpty();
    }
}
