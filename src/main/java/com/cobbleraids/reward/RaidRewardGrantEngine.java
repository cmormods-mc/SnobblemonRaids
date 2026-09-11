package com.cobbleraids.reward;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRewardPolicyManager;
import com.cobbleraids.reward.currency.RaidCurrencyBackends;
import com.cobbleraids.reward.plan.RewardPlan;
import com.cobbleraids.reward.plan.RewardPlanResolver;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative item grant logic. SkiesGUIs never grants raid loot directly. */
public final class RaidRewardGrantEngine {
    private RaidRewardGrantEngine() {}

    /**
     * Grants one claim.
     *
     * <p>Nothing here decides what a reward is. {@link RewardPlanResolver} does that, once, and
     * hands back a plan; this rolls it. The split matters because the two used to be tangled: this
     * method read a loot-table field on the choice and another on the rewards block and rolled
     * both, so a definition carrying both handed out both. A plan has one table list.
     */
    public static RewardGrantResult grantChoice(ServerPlayer player, PendingRaidReward pending,
                                                RaidDefinition.RewardChoice choice) {
        ResourceLocation definitionId = pending.definitionId();
        RaidDefinition definition = RaidDefinitionRegistry.get(definitionId);
        String species = definition == null ? null : definition.species().getPath();

        RewardPlan plan = RewardPlanResolver.resolve(
                pending.rewards(), choice, pending.rarityTier(), species,
                pending.contributionPercentage(), pending.contributionBonusRolls(),
                RaidRewardPolicyManager.get(), CobbleRaidsConfigManager.get().currency(),
                table -> RaidLootRoller.exists(player, parse(table, definitionId)));

        // One generator per claim, seeded from the claim token, handing a fresh sub-seed to each
        // selection. Reusing the claim seed directly for every roll would make all of a bundle's
        // general selections identical -- reproducible, and useless.
        Random claimRandom = new Random(pending.rewardSeed());
        RewardGrantResult result = switch (plan) {
            case RewardPlan.Policy policy -> grantPolicy(player, definitionId, policy, claimRandom);
            case RewardPlan.Legacy legacy -> grantLegacy(player, definitionId, legacy, claimRandom);
        };
        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("" + definitionId + " granted via the " + plan.mode() + " path: "
                    + plan.lootTables().size() + " table roll(s)");
        }
        return result;
    }

    /** Policy path: roll each table in the plan once, then pay. Every selection is one table roll. */
    private static RewardGrantResult grantPolicy(ServerPlayer player, ResourceLocation definitionId,
                                                 RewardPlan.Policy plan, Random claimRandom) {
        List<RaidDefinition.RewardItem> standard = new ArrayList<>();
        List<RaidDefinition.RewardItem> bonus = new ArrayList<>();
        // The plan lists the specialty table first, then one general table per selection. The last
        // `bonusGeneralRolls` of those are what contribution earned, and are reported separately so
        // the claim message can say what the player's damage share actually bought them.
        int firstBonusIndex = plan.lootTables().size() - plan.bonusGeneralRolls();
        for (int index = 0; index < plan.lootTables().size(); index++) {
            ResourceLocation tableId = parse(plan.lootTables().get(index), definitionId);
            if (tableId == null) continue;
            List<RaidDefinition.RewardItem> rolled = RaidLootRoller.rollAll(
                    player, List.of(tableId), definitionId, nextSeed(claimRandom));
            (index >= firstBonusIndex ? bonus : standard).addAll(rolled);
        }
        return new RewardGrantResult(standard, List.of(), bonus, payCurrency(player, definitionId, plan.currency()));
    }

    /** Legacy path: exactly what a hand-written definition did before the policy existed. */
    private static RewardGrantResult grantLegacy(ServerPlayer player, ResourceLocation definitionId,
                                                 RewardPlan.Legacy plan, Random claimRandom) {
        List<RaidDefinition.RewardItem> base = new ArrayList<>();
        for (RaidDefinition.RewardItem item : plan.items()) {
            if (give(player, item, definitionId)) base.add(item);
        }
        List<RaidDefinition.RewardItem> chanceGranted = new ArrayList<>();
        for (RaidDefinition.RewardItem item : plan.chanceItems()) {
            if (claimRandom.nextDouble() < item.chance() && give(player, item, definitionId)) {
                chanceGranted.add(item);
            }
        }
        // Loot tables borrow another mod's own balancing instead of restating it item by item.
        List<ResourceLocation> tables = new ArrayList<>();
        for (String table : plan.lootTables()) {
            ResourceLocation parsed = parse(table, definitionId);
            if (parsed != null) tables.add(parsed);
        }
        base.addAll(RaidLootRoller.rollAll(player, tables, definitionId, nextSeed(claimRandom)));

        List<RaidDefinition.RewardItem> bonusGranted = new ArrayList<>();
        for (int index = 0; index < plan.bonusRolls() && !plan.bonusPool().isEmpty(); index++) {
            RaidDefinition.RewardItem rolled = weighted(plan.bonusPool(), claimRandom);
            if (give(player, rolled, definitionId)) bonusGranted.add(rolled);
        }
        return new RewardGrantResult(base, chanceGranted, bonusGranted,
                payCurrency(player, definitionId, plan.currency()));
    }

    private static ResourceLocation parse(String tableId, ResourceLocation definitionId) {
        ResourceLocation parsed = ResourceLocation.tryParse(tableId);
        if (parsed == null) {
            RaidLog.error("" + definitionId + " names loot table '" + tableId
                    + "', which is not a valid resource location; skipping it.");
        }
        return parsed;
    }

    /** RANDOMIZE_SEED means "roll freely" to the loot API, so it must never be handed out as a seed. */
    private static long nextSeed(Random claimRandom) {
        long seed = claimRandom.nextLong();
        return seed == net.minecraft.world.level.storage.loot.LootTable.RANDOMIZE_SEED ? 1L : seed;
    }

    static RaidDefinition.RewardItem weighted(List<RaidDefinition.RewardItem> pool, Random random) {
        long total = 0;
        for (RaidDefinition.RewardItem item : pool) total += item.weight();
        long roll = Math.floorMod(random.nextLong(), total);
        for (RaidDefinition.RewardItem item : pool) {
            roll -= item.weight();
            if (roll < 0) return item;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * Credits the claim's currency, if any, through whichever economy backend is active.
     *
     * <p>Runs last and cannot throw. RaidRewardService restores the whole claim when granting
     * throws, so a failure here after the items were placed would return a claim the player has
     * already been paid for -- the same duplication hazard give() documents. The backend contract
     * is to report false rather than throw; this guard is the belt to that braces, because the
     * backend is the one place in this path that calls into another mod.
     */
    private static BigInteger payCurrency(ServerPlayer player, ResourceLocation definitionId, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        try {
            if (RaidCurrencyBackends.active().grant(player, amount)) return amount;
            RaidLog.warn("Raid currency payout of " + amount + " for " + definitionId
                    + " was not credited by backend '" + RaidCurrencyBackends.active().name()
                    + "'; the item rewards were granted normally.");
            return BigInteger.ZERO;
        } catch (RuntimeException ex) {
            RaidLog.error("Raid currency payout failed for " + definitionId
                    + "; the item rewards were granted normally.", ex);
            return BigInteger.ZERO;
        }
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
            RaidLog.error("" + definitionId + " reward item '" + reward.item()
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
