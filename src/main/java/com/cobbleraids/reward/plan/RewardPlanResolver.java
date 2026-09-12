package com.cobbleraids.reward.plan;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidRewardPolicy;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.RaidMegaPity;
import com.cobbleraids.reward.currency.RaidCurrencyPolicy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The one place that decides what a claim grants.
 *
 * <p>Every input is a plain value and no Minecraft type appears, so the whole decision -- which
 * path a definition is on, how many selections it is worth, which tables those are, what currency
 * it pays -- is unit-testable without a server. The grant engine is left with nothing to decide:
 * it rolls the list it is handed.
 */
public final class RewardPlanResolver {

    private RewardPlanResolver() {}

    /**
     * True when a definition describes its own rewards, and so keeps its own behaviour.
     *
     * <p>One rule, in one place. Anything a hand-written definition can put in its rewards block
     * counts: items, chance items, loot tables at either level, or an enabled contribution pool.
     * A definition that names none of those has nothing to contribute to the decision, which is
     * precisely what makes it safe for the policy to decide instead.
     */
    public static boolean isSelfDescribing(RaidDefinition.Rewards rewards, RaidDefinition.RewardChoice choice) {
        if (rewards == null) return false;
        if (!rewards.lootTables().isEmpty()) return true;
        RaidDefinition.ContributionBonus bonus = rewards.contributionBonus();
        if (bonus != null && bonus.enabled() && !bonus.pool().isEmpty()) return true;
        if (choice == null) return false;
        return !choice.items().isEmpty() || !choice.chanceItems().isEmpty() || !choice.lootTables().isEmpty();
    }

    /**
     * How many bonus general selections a damage share earns.
     *
     * <p>Which thresholds apply follows the same split as everything else: a self-describing
     * definition uses the ones written into it, and a policy-driven one uses the server's. Keeping
     * the choice here rather than at the victory site is what stops the two drifting -- when the
     * bundled definitions were migrated their inline contribution blocks went with them, and a
     * victory site that only knew about inline blocks awarded every player zero bonus rolls while
     * looking entirely correct.
     */
    public static int bonusRollsFor(RaidDefinition.Rewards rewards, RaidDefinition.RewardChoice choice,
                                    RaidRewardPolicy policy, double contributionPercentage) {
        if (isSelfDescribing(rewards, choice)) {
            RaidDefinition.ContributionBonus bonus = rewards.contributionBonus();
            if (bonus == null || !bonus.enabled()) return 0;
            List<ContributionMath.Threshold> thresholds = bonus.tiers().stream()
                    .map(tier -> new ContributionMath.Threshold(tier.minPercentage(), tier.bonusRolls()))
                    .toList();
            return ContributionMath.bonusRolls(contributionPercentage, thresholds);
        }
        return ContributionMath.bonusRolls(contributionPercentage, policy.contributionThresholds());
    }

    /**
     * Builds the plan for one claim.
     *
     * @param tableExists whether a loot table id is loaded. Only consulted for the boss-specific
     *                    specialty table: 21 of 130 bosses have one, and asking is how the other
     *                    109 fall back to their tier's table without either side hardcoding which
     *                    is which.
     */
    public static RewardPlan resolve(RaidDefinition.Rewards rewards,
                                     RaidDefinition.RewardChoice choice,
                                     RaidRarityTier tier,
                                     String species,
                                     double contributionPercentage,
                                     int bonusRolls,
                                     RaidRewardPolicy policy,
                                     CobbleRaidsConfig.Currency currencyConfig,
                                     Predicate<String> tableExists,
                                     int raidsSinceMegaStone,
                                     CobbleRaidsConfig.MegaPity pityConfig) {
        BigInteger currency = RaidCurrencyPolicy.payout(currencyConfig, tier, contributionPercentage);

        if (isSelfDescribing(rewards, choice)) {
            List<String> tables = new ArrayList<>();
            // Both levels, deliberately: a hand-written definition rolling its choice's tables and
            // the rewards-level ones together is the documented behaviour, and preserving it is the
            // point of keeping this path at all. Composed here rather than at two call sites.
            for (var table : choice.lootTables()) tables.add(table.toString());
            for (var table : rewards.lootTables()) tables.add(table.toString());
            RaidDefinition.ContributionBonus bonus = rewards.contributionBonus();
            boolean poolActive = bonus != null && bonus.enabled() && !bonus.pool().isEmpty();
            return new RewardPlan.Legacy(
                    choice.items(),
                    choice.chanceItems(),
                    tables,
                    poolActive ? bonus.pool() : List.of(),
                    poolActive ? Math.max(0, bonusRolls) : 0,
                    currency);
        }

        String bossTable = species == null || species.isBlank() ? null
                : policy.bossSpecialtyTableFor(species);
        boolean megaCapable = bossTable != null && tableExists != null && tableExists.test(bossTable);

        List<String> tables = new ArrayList<>();
        if (megaCapable && RaidMegaPity.guaranteed(raidsSinceMegaStone, pityConfig)) {
            // The guarantee replaces the specialty selection rather than adding one: a player owed
            // a stone gets a stone instead of that roll, not as well as it, so the bundle is always
            // the same size and nothing else in the pool is displaced.
            tables.add(megaTableFor(bossTable));
        } else {
            tables.add(megaCapable ? bossTable : policy.specialtyTableFor(tier));
        }
        int generalRolls = policy.standardGeneralRolls() + Math.max(0, bonusRolls);
        String general = policy.generalTableFor(tier);
        for (int index = 0; index < generalRolls; index++) tables.add(general);
        return new RewardPlan.Policy(tables, Math.max(0, bonusRolls), currency, megaCapable);
    }

    /** The boss's stone table, derived from its specialty table so the two cannot drift apart. */
    static String megaTableFor(String bossSpecialtyTable) {
        return bossSpecialtyTable.replace("specialty/boss/", "specialty/mega/");
    }

    /**
     * The boss's own specialty table when one is loaded, otherwise the tier's.
     *
     * <p>Asking whether the table exists, rather than consulting a list of which bosses have a Mega
     * Stone, means adding a stone to a boss is a datapack change and nothing here has to learn
     * about it.
     */
    private static String specialtyTableFor(RaidRewardPolicy policy, RaidRarityTier tier, String species,
                                            Predicate<String> tableExists) {
        if (species != null && !species.isBlank() && tableExists != null) {
            String bossTable = policy.bossSpecialtyTableFor(species.toLowerCase(Locale.ROOT));
            if (tableExists.test(bossTable)) return bossTable;
        }
        return policy.specialtyTableFor(tier);
    }
}
