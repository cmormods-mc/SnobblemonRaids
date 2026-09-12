package com.cobbleraids.reward.plan;

import com.cobbleraids.config.RaidDefinition;
import java.math.BigInteger;
import java.util.List;

/**
 * Everything one claim will grant, decided in one place before anything is granted.
 *
 * <p>It is sealed with exactly two shapes because a definition is exactly one of two things, and
 * mixing them is the bug this type exists to make unrepresentable. Before it, the grant engine read
 * two separate loot-table fields and rolled both -- a definition that ended a migration with both
 * set handed out both, silently. A plan has one table list, built by one resolver, so there is no
 * second source to disagree with the first.
 *
 * <p>Rejecting the mixed case at load time was the other option and is worse here: the definition
 * registry skips a malformed definition and carries on, so "reject" would mean that boss quietly
 * stops spawning. Trading duplicated loot for a raid that never happens makes the failure quieter,
 * not smaller. Classification, plus an audit finding for anything still on the legacy path, keeps
 * the boss in the game and names the problem.
 */
public sealed interface RewardPlan permits RewardPlan.Policy, RewardPlan.Legacy {

    /** Loot tables to roll, in order, one result each. The list length is the selection count. */
    List<String> lootTables();

    /** Currency to credit, decided by RaidCurrencyPolicy; zero when nothing is configured. */
    BigInteger currency();

    /** For logs and the consistency audit: which path produced this plan. */
    String mode();

    /**
     * A definition that names no rewards of its own. The tables come from the server-wide policy,
     * keyed by rarity tier and, where such a table exists, by species.
     *
     * @param lootTables the specialty table, then the key fragment table once per fragment the
     *                   claim earns, then the general table repeated
     *                   {@code standardGeneralRolls + bonusGeneralRolls} times. The general rolls
     *                   are last because the grant engine reports the final
     *                   {@code bonusGeneralRolls} of the list as contribution-earned -- anything
     *                   added after them is reported as the contribution bonus instead.
     */
    record Policy(List<String> lootTables, int bonusGeneralRolls, BigInteger currency,
                  boolean megaCapable) implements RewardPlan {
        public Policy {
            lootTables = List.copyOf(lootTables);
            currency = currency == null ? BigInteger.ZERO : currency;
        }

        @Override
        public String mode() {
            return "policy";
        }
    }

    /**
     * A definition that names its own items, chance items, loot tables or contribution pool. Its
     * behaviour is preserved exactly as it was, including the rewards-level and per-choice tables
     * being rolled together -- that combination is a documented feature for a hand-written
     * definition, and only a hazard when a migration leaves it behind by accident.
     */
    record Legacy(List<RaidDefinition.RewardItem> items,
                  List<RaidDefinition.RewardItem> chanceItems,
                  List<String> lootTables,
                  List<RaidDefinition.RewardItem> bonusPool,
                  int bonusRolls,
                  BigInteger currency) implements RewardPlan {
        public Legacy {
            items = List.copyOf(items);
            chanceItems = List.copyOf(chanceItems);
            lootTables = List.copyOf(lootTables);
            bonusPool = List.copyOf(bonusPool);
            currency = currency == null ? BigInteger.ZERO : currency;
        }

        @Override
        public String mode() {
            return "legacy";
        }
    }
}
