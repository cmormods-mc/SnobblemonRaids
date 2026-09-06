package com.cobbleraids.lifecycle;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.PendingRaidReward;
import com.cobbleraids.reward.RaidRewardGrantEngine;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.reward.RewardGrantResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
        boolean opened = RewardGuiBackends.active().open(player, pending.rewards().guiId());
        if (!opened) sendChatFallbackChoices(player, pending);
        return true;
    }

    private static void sendChatFallbackChoices(ServerPlayer player, PendingRaidReward pending) {
        player.sendSystemMessage(Component.literal("Reward GUI unavailable; choose with /cobbleraids reward claim <id>:"));
        for (String choiceId : pending.rewards().choices().keySet()) {
            player.sendSystemMessage(Component.literal(" - " + choiceId));
        }
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
            RewardGrantResult result = RaidRewardGrantEngine.grantChoice(player, pending, choice);
            if (CobbleRaidsConfigManager.get().debugLogging()) {
                System.out.println("[CobbleRaids] " + player.getGameProfile().getName() + " claimed '" + choiceId
                        + "' for raid " + pending.definitionId() + ": base=" + summarize(result.baseItems())
                        + " chance=" + summarize(result.chanceItemsGranted())
                        + " bonus=" + summarize(result.contributionBonusItems()));
            }
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Raid reward claimed. Contribution %.1f%% awarded %d bonus roll%s. Granted: %s",
                    pending.contributionPercentage(), pending.contributionBonusRolls(),
                    pending.contributionBonusRolls() == 1 ? "" : "s", describeAll(result))));
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

    private static String summarize(List<RaidDefinition.RewardItem> items) {
        if (items.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (RaidDefinition.RewardItem item : items) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(item.item()).append(" x").append(item.amount());
        }
        return builder.toString();
    }

    private static String describeAll(RewardGrantResult result) {
        List<RaidDefinition.RewardItem> all = result.allGranted();
        if (all.isEmpty()) return "nothing";
        StringBuilder builder = new StringBuilder();
        for (RaidDefinition.RewardItem item : all) {
            if (builder.length() > 0) builder.append(", ");
            Item resolved = BuiltInRegistries.ITEM.get(item.item());
            builder.append(new ItemStack(resolved).getHoverName().getString()).append(" x").append(item.amount());
        }
        return builder.toString();
    }
}
