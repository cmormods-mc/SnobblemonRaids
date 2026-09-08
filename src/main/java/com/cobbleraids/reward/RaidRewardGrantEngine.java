package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative item grant logic. SkiesGUIs never grants raid loot directly. */
public final class RaidRewardGrantEngine {
    private RaidRewardGrantEngine() {}

    public static RewardGrantResult grantChoice(ServerPlayer player, PendingRaidReward pending, RaidDefinition.RewardChoice choice) {
        ResourceLocation definitionId = pending.definitionId();
        List<RaidDefinition.RewardItem> base = new ArrayList<>();
        for (RaidDefinition.RewardItem item : choice.items()) {
            if (give(player, item, definitionId)) base.add(item);
        }
        List<RaidDefinition.RewardItem> chanceGranted = new ArrayList<>();
        for (RaidDefinition.RewardItem item : choice.chanceItems()) {
            if (ThreadLocalRandom.current().nextDouble() < item.chance() && give(player, item, definitionId)) {
                chanceGranted.add(item);
            }
        }
        // Loot tables borrow another mod's own balancing instead of restating it item by item.
        // Choice tables belong to the option the player picked; the rewards-level ones are rolled
        // for every victor whatever they chose.
        base.addAll(RaidLootRoller.rollAll(player, choice.lootTables(), definitionId));
        base.addAll(RaidLootRoller.rollAll(player, pending.rewards().lootTables(), definitionId));
        List<RaidDefinition.RewardItem> bonusGranted = new ArrayList<>();
        RaidDefinition.ContributionBonus bonus = pending.rewards().contributionBonus();
        if (bonus.enabled() && pending.contributionBonusRolls() > 0 && !bonus.pool().isEmpty()) {
            for (int i = 0; i < pending.contributionBonusRolls(); i++) {
                RaidDefinition.RewardItem rolled = weighted(bonus.pool());
                if (give(player, rolled, definitionId)) bonusGranted.add(rolled);
            }
        }
        return new RewardGrantResult(base, chanceGranted, bonusGranted);
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

    /**
     * Places one reward line in the inventory. Returns false, having granted nothing, when the item
     * does not resolve.
     *
     * <p>It used to throw. That was actively harmful once a definition could name another mod's
     * item: RaidRewardService consumes the claim before granting and restores it if granting
     * throws, so an unresolvable item halfway down a list meant the player kept everything already
     * handed out AND kept the claim -- a duplication bug on every retry, and a reward that could
     * never be completed. Skipping the line leaves the rest of the reward intact and the claim
     * properly spent, which is what a modpack that has just dropped a mod needs.
     */
    private static boolean give(ServerPlayer player, RaidDefinition.RewardItem reward, ResourceLocation definitionId) {
        Item item = BuiltInRegistries.ITEM.get(reward.item());
        if (!BuiltInRegistries.ITEM.getKey(item).equals(reward.item())) {
            System.err.println("[CobbleRaids] " + definitionId + " reward item '" + reward.item()
                    + "' is not registered; skipping it. Is the mod that owns it installed?");
            return false;
        }
        int remaining = reward.amount();
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int amount = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(amount);
            player.getInventory().placeItemBackInInventory(stack);
            remaining -= amount;
        }
        return true;
    }
}
