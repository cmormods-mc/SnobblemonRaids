package com.cobbleraids.reward;

import com.cobbleraids.lifecycle.RaidRewardService;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Player-facing claim endpoint used by SkiesGUIs buttons and for reopening a closed reward GUI. */
public final class RaidRewardCommand {
    private RaidRewardCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("reward")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    return RaidRewardService.openCurrent(player) ? 1 : 0;
                                })
                                .then(Commands.literal("claim")
                                        .then(Commands.argument("choice", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                    return RaidRewardService.claim(player, StringArgumentType.getString(ctx, "choice")) ? 1 : 0;
                                                }))))
        ));
    }
}
