package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidRewardPolicyManager;
import com.cobbleraids.shop.ShopCatalogManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Lets an operator re-read every hand-edited JSON CobbleRaids owns -- server.json, the reward
 * policy, and the shop catalogue -- without a full datapack /reload.
 *
 * <p>The three are independent files with independent failure modes, so each gets its own
 * try/catch: a malformed shop catalogue must not stop the config and reward-policy reloads that
 * already succeeded from taking effect, matching how the three run as three separate
 * START_DATA_PACK_RELOAD listeners in CobbleRaids.java rather than one. This used to only reload
 * CobbleRaidsConfigManager, silently leaving the reward policy and shop catalogue stale -- exactly
 * the gap a full /reload does not have.
 */
final class RaidAdminConfigOps {
    private RaidAdminConfigOps() {}

    static int reload(CommandSourceStack source) {
        boolean configOk = reloadOne(source, "CobbleRaids config", CobbleRaidsConfigManager::reload);
        boolean policyOk = reloadOne(source, "reward policy", RaidRewardPolicyManager::reload);
        boolean catalogOk = reloadOne(source, "shop catalogue", ShopCatalogManager::reload);

        if (!(configOk && policyOk && catalogOk)) return 0;
        source.sendSuccess(() -> Component.literal("Reloaded the CobbleRaids config, reward policy and shop"
                + " catalogue from " + CobbleRaidsConfigManager.path().getParent()).withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static boolean reloadOne(CommandSourceStack source, String label, Runnable reload) {
        try {
            reload.run();
            return true;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to reload the " + label + ": " + ex.getMessage()));
            return false;
        }
    }
}
