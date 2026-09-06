package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition.RewardItem;
import java.util.ArrayList;
import java.util.List;

/**
 * What grantChoice actually placed in a player's inventory, split by the category responsible for
 * each line: guaranteed base items, chance items that hit their roll, and contribution-bonus pool
 * rolls. Built alongside the exact same give()/RNG calls grantChoice already made -- this type only
 * reports what was granted, it never changes it.
 */
public record RewardGrantResult(
        List<RewardItem> baseItems,
        List<RewardItem> chanceItemsGranted,
        List<RewardItem> contributionBonusItems
) {
    public RewardGrantResult {
        baseItems = List.copyOf(baseItems);
        chanceItemsGranted = List.copyOf(chanceItemsGranted);
        contributionBonusItems = List.copyOf(contributionBonusItems);
    }

    public List<RewardItem> allGranted() {
        List<RewardItem> all = new ArrayList<>(baseItems.size() + chanceItemsGranted.size() + contributionBonusItems.size());
        all.addAll(baseItems);
        all.addAll(chanceItemsGranted);
        all.addAll(contributionBonusItems);
        return all;
    }
}
