package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Lets an operator re-read config/cobbleraids/server.json without a full datapack /reload. */
final class RaidAdminConfigOps {
    private RaidAdminConfigOps() {}

    static int reload(CommandSourceStack source) {
        try {
            CobbleRaidsConfigManager.reload();
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to reload CobbleRaids config: " + ex.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Reloaded CobbleRaids config from "
                + CobbleRaidsConfigManager.path()).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
