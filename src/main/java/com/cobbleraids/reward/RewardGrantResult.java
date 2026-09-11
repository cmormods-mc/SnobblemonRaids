package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition.RewardItem;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * What grantChoice actually placed in a player's inventory, split by the category responsible for
 * each line: guaranteed base items, chance items that hit their roll, and contribution-bonus pool
 * rolls. Built alongside the exact same give()/RNG calls grantChoice already made -- this type only
 * reports what was granted, it never changes it.
 *
 * <p>{@code currencyGranted} is what an economy backend actually credited, not what the policy
 * asked for: a payout the backend refused, or that no installed mod could pay, reports as zero.
 */
public record RewardGrantResult(
        List<RewardItem> baseItems,
        List<RewardItem> chanceItemsGranted,
        List<RewardItem> contributionBonusItems,
        BigInteger currencyGranted
) {
    public RewardGrantResult {
        baseItems = List.copyOf(baseItems);
        chanceItemsGranted = List.copyOf(chanceItemsGranted);
        contributionBonusItems = List.copyOf(contributionBonusItems);
        currencyGranted = currencyGranted == null ? BigInteger.ZERO : currencyGranted;
    }

    /** True when this claim paid currency, so callers can leave it out of a message entirely. */
    public boolean paidCurrency() {
        return currencyGranted.signum() > 0;
    }

    public List<RewardItem> allGranted() {
        List<RewardItem> all = new ArrayList<>(baseItems.size() + chanceItemsGranted.size() + contributionBonusItems.size());
        all.addAll(baseItems);
        all.addAll(chanceItemsGranted);
        all.addAll(contributionBonusItems);
        return all;
    }
}
