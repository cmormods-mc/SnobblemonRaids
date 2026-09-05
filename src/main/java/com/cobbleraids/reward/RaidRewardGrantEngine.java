package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative item grant logic. SkiesGUIs never grants raid loot directly. */
public final class RaidRewardGrantEngine {
    private RaidRewardGrantEngine() {}

    public static void grantChoice(ServerPlayer player, PendingRaidReward pending, RaidDefinition.RewardChoice choice) {
        for (RaidDefinition.RewardItem item : choice.items()) give(player, item);
        for (RaidDefinition.RewardItem item : choice.chanceItems()) {
            if (ThreadLocalRandom.current().nextDouble() < item.chance()) give(player, item);
        }
        RaidDefinition.ContributionBonus bonus = pending.rewards().contributionBonus();
        if (bonus.enabled() && pending.contributionBonusRolls() > 0 && !bonus.pool().isEmpty()) {
            for (int i = 0; i < pending.contributionBonusRolls(); i++) give(player, weighted(bonus.pool()));
        }
    }

    static RaidDefinition.RewardItem weighted(List<RaidDefinition.RewardItem> pool) {
        long total = 0;
        for (RaidDefinition.RewardItem item : pool) total += item.weight();
        long roll = ThreadLocalRandom.current().nextLong(total);
        for (RaidDefinition.RewardItem item : pool) {
            roll -= item.weight();
            if (roll < 0) return item;
        }
        return pool.get(pool.size() - 1);
    }

    private static void give(ServerPlayer player, RaidDefinition.RewardItem reward) {
        Item item = BuiltInRegistries.ITEM.get(reward.item());
        if (!BuiltInRegistries.ITEM.getKey(item).equals(reward.item())) {
            throw new IllegalStateException("Unknown reward item: " + reward.item());
        }
        int remaining = reward.amount();
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int amount = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(amount);
            player.getInventory().placeItemBackInInventory(stack);
            remaining -= amount;
        }
    }
}
