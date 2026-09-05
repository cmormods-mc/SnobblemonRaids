package com.cobbleraids.lifecycle;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.PendingRaidReward;
import com.cobbleraids.reward.RaidRewardGrantEngine;
import com.pokeskies.skiesguis.api.SkiesGUIsAPI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-authoritative reward queue. SkiesGUIs is presentation only: all claims are validated here,
 * consumed exactly once, and then granted from the raid definition snapshot.
 */
public final class RaidRewardService {
    private static final Map<UUID, ArrayDeque<PendingRaidReward>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> OPEN_DELAY = new ConcurrentHashMap<>();
    private static final int GUI_OPEN_DELAY_TICKS = 2;
    private RaidRewardService() {}

    public static void grant(RaidRewardEligibility eligibility, MinecraftServer server) {
        if (eligibility == null || eligibility.outcome() != RaidOutcome.VICTORY || server == null) return;
        RaidDefinition definition = RaidDefinitionRegistry.get(eligibility.definitionId());
        if (definition == null) {
            System.err.println("[CobbleRaids] Cannot create rewards for " + eligibility.raidId() + ": definition " + eligibility.definitionId() + " is not loaded");
            return;
        }
        RaidDefinition.Rewards rewards = definition.rewards();
        if (rewards.choices().isEmpty()) {
            System.err.println("[CobbleRaids] Raid " + definition.id() + " has no GUI reward choices; no claim GUI queued");
            return;
        }

        Map<UUID, Double> percentages = ContributionMath.percentages(eligibility.contribution(), eligibility.participants());
        List<ContributionMath.Threshold> thresholds = rewards.contributionBonus().tiers().stream()
                .map(t -> new ContributionMath.Threshold(t.minPercentage(), t.bonusRolls()))
                .toList();

        for (UUID playerId : eligibility.participants()) {
            double percentage = percentages.getOrDefault(playerId, 0.0);
            int bonusRolls = rewards.contributionBonus().enabled()
                    ? ContributionMath.bonusRolls(percentage, thresholds) : 0;
            PendingRaidReward pending = new PendingRaidReward(
                    eligibility.raidId(), eligibility.definitionId(), rewards, percentage, bonusRolls);
            PENDING.computeIfAbsent(playerId, ignored -> new ArrayDeque<>()).addLast(pending);
            if (server.getPlayerList().getPlayer(playerId) != null) OPEN_DELAY.putIfAbsent(playerId, GUI_OPEN_DELAY_TICKS);
        }
    }

    /** Called from END_SERVER_TICK so the battle-end screen packet is processed before the reward chest opens. */
    public static void tick(MinecraftServer server) {
        // OPEN_DELAY is empty on almost every tick (only populated for ~2 ticks after a victory), so
        // skip the ConcurrentHashMap iteration entirely rather than paying for it 20x/second at idle.
        if (OPEN_DELAY.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Integer>> it = OPEN_DELAY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) openCurrent(player);
        }
    }

    public static boolean hasPending(UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(playerId);
        return queue != null && !queue.isEmpty();
    }

    public static boolean openCurrent(ServerPlayer player) {
        PendingRaidReward pending = peek(player.getUUID());
        if (pending == null) {
            player.sendSystemMessage(Component.literal("You do not have an unclaimed raid reward."));
            return false;
        }
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Raid contribution: %.1f%% | Contribution bonus rolls: %d",
                pending.contributionPercentage(), pending.contributionBonusRolls())));
        SkiesGUIsAPI.INSTANCE.attemptGUIOpen(player, pending.rewards().guiId());
        return true;
    }

    public static synchronized boolean claim(ServerPlayer player, String choiceId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(player.getUUID());
        PendingRaidReward pending = queue == null ? null : queue.peekFirst();
        if (pending == null) {
            player.sendSystemMessage(Component.literal("You do not have an unclaimed raid reward."));
            return false;
        }
        RaidDefinition.RewardChoice choice = pending.rewards().choices().get(choiceId);
        if (choice == null) {
            player.sendSystemMessage(Component.literal("That reward choice is not valid for this raid."));
            return false;
        }

        // Consume before granting so duplicate GUI/command clicks cannot double-spend the claim token.
        queue.removeFirst();
        if (queue.isEmpty()) PENDING.remove(player.getUUID());
        try {
            RaidRewardGrantEngine.grantChoice(player, pending, choice);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Raid reward claimed. Contribution %.1f%% awarded %d bonus roll%s.",
                    pending.contributionPercentage(), pending.contributionBonusRolls(),
                    pending.contributionBonusRolls() == 1 ? "" : "s")));
            if (hasPending(player.getUUID())) OPEN_DELAY.put(player.getUUID(), GUI_OPEN_DELAY_TICKS);
            return true;
        } catch (RuntimeException ex) {
            // Restore the exact claim at the front if granting fails before completion.
            PENDING.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>()).addFirst(pending);
            player.sendSystemMessage(Component.literal("Raid reward grant failed; your claim was preserved. Contact an administrator."));
            throw ex;
        }
    }

    private static PendingRaidReward peek(UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(playerId);
        return queue == null ? null : queue.peekFirst();
    }
}
