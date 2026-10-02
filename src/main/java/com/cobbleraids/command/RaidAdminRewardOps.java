package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lifecycle.RaidOutcome;
import com.cobbleraids.lifecycle.RaidRewardEligibility;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.reward.PendingRaidReward;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Manually queues one raid definition's reward-choice GUI for a player at full (100%)
 * contribution, e.g. to compensate someone after a crashed or aborted raid.
 */
final class RaidAdminRewardOps {
    private RaidAdminRewardOps() {}

    static int grant(CommandSourceStack source, ServerPlayer target, ResourceLocation definitionId) {
        RaidDefinition definition = RaidDefinitionRegistry.get(definitionId);
        if (definition == null) {
            source.sendFailure(Component.literal("No loaded raid definition '" + definitionId + "'. Use /cobbleraids list."));
            return 0;
        }
        if (definition.rewards().choices().isEmpty()) {
            source.sendFailure(Component.literal(definitionId + " has no reward choices configured."));
            return 0;
        }

        UUID playerId = target.getUUID();
        RaidRewardEligibility eligibility = new RaidRewardEligibility(
                UUID.randomUUID(), definitionId, RaidOutcome.VICTORY, Map.of(playerId, 1.0f), Set.of(playerId), 0, "", "");
        RaidRewardService.grant(eligibility, source.getServer());

        source.sendSuccess(() -> Component.literal("Queued " + CommandFormat.shortId(definitionId)
                + " reward choices for " + target.getGameProfile().getName() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** One player's unclaimed queue, or a roster of everyone holding one when no player is given. */
    static int list(CommandSourceStack source, ServerPlayer target) {
        if (target == null) return listAll(source);

        List<PendingRaidReward> queue = RaidRewardService.pendingFor(target.getUUID());
        String name = target.getGameProfile().getName();
        if (queue.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + " has no unclaimed raid rewards.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header(name + "'s unclaimed rewards (" + queue.size() + ")"), false);
        int index = 1;
        for (PendingRaidReward pending : queue) {
            int position = index++;
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(position + ".", 4)
                            + CommandFormat.pad(CommandFormat.shortId(pending.definitionId()), 16)
                            + CommandFormat.pad(pending.rarityTier().serializedName(), 11)
                            + CommandFormat.percent(pending.contributionPercentage()) + " · +"
                            + pending.contributionBonusRolls() + " rolls")
                    .withStyle(RaidTierPresentation.color(pending.rarityTier())), false);
        }
        // Only the front of the queue is claimable, so say so rather than implying all of them are.
        if (queue.size() > 1) {
            source.sendSuccess(() -> CommandFormat.hint(" only #1 is claimable until it is taken"), false);
        }
        return queue.size();
    }

    private static int listAll(CommandSourceStack source) {
        return PendingHolderCommands.listHolders(source, RaidRewardService.playersWithPending(),
                playerId -> RaidRewardService.pendingFor(playerId).size(),
                "Nobody has an unclaimed raid reward.", "unclaimed rewards");
    }

    static int clear(CommandSourceStack source, ServerPlayer target) {
        int removed = RaidRewardService.clearPending(target.getUUID(), source.getServer());
        return PendingHolderCommands.reportCleared(source, target, removed, "unclaimed raid rewards",
                "unclaimed raid reward(s)", "");
    }
}
