package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lifecycle.RaidOutcome;
import com.cobbleraids.lifecycle.RaidRewardEligibility;
import com.cobbleraids.lifecycle.RaidRewardService;
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
                UUID.randomUUID(), definitionId, RaidOutcome.VICTORY, Map.of(playerId, 1.0f), Set.of(playerId));
        RaidRewardService.grant(eligibility, source.getServer());

        source.sendSuccess(() -> Component.literal("Queued " + definitionId + " reward choices for "
                + target.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
