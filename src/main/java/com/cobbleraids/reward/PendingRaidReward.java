package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable per-player claim token. The full reward config is snapshotted so /reload cannot rewrite an earned reward. */
public record PendingRaidReward(
        UUID raidId,
        ResourceLocation definitionId,
        RaidDefinition.Rewards rewards,
        double contributionPercentage,
        int contributionBonusRolls
) {}
