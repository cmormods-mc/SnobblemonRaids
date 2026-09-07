package com.cobbleraids.reward;

import com.cobbleraids.lifecycle.RaidRewardService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/** Player-facing claim endpoint used by SkiesGUIs buttons and for reopening a closed reward GUI. */
public final class RaidRewardCommand {
    /**
     * Suggests only what the caller can actually claim, which is the choices on their own
     * front-of-queue reward. Console has no player, so it gets nothing rather than an error.
     */
    private static final SuggestionProvider<CommandSourceStack> CLAIMABLE_CHOICES = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        return player == null
                ? builder.buildFuture()
                : SharedSuggestionProvider.suggest(
                        RaidRewardService.pendingChoiceIds(player.getUUID()).stream().sorted(), builder);
    };

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
                                                .suggests(CLAIMABLE_CHOICES)
                                                .executes(ctx -> {
                                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                    return RaidRewardService.claim(player, StringArgumentType.getString(ctx, "choice")) ? 1 : 0;
                                                }))))
        ));
    }
}
