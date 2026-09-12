package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.reward.points.RaidPointsStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reading and administering Raid Point balances.
 *
 * <p>Player-facing half is one command with no arguments, because until the shop exists a balance
 * is the only thing a player can do with points and they should not have to learn a syntax to see
 * it. The admin half is what makes the shop testable before it is written: a balance that can only
 * be earned four raids at a time is a balance nobody can test a price list against.
 */
public final class RaidPointsCommand {

    private RaidPointsCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("points")
                                .executes(context -> ownBalance(context.getSource()))
                                .then(Commands.literal("give")
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(context -> give(context.getSource(),
                                                                EntityArgument.getPlayer(context, "target"),
                                                                IntegerArgumentType.getInteger(context, "amount"))))))
                                .then(Commands.literal("take")
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(context -> take(context.getSource(),
                                                                EntityArgument.getPlayer(context, "target"),
                                                                IntegerArgumentType.getInteger(context, "amount"))))))
                                .then(Commands.literal("set")
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                                        .executes(context -> set(context.getSource(),
                                                                EntityArgument.getPlayer(context, "target"),
                                                                IntegerArgumentType.getInteger(context, "amount"))))))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> otherBalance(context.getSource(),
                                                EntityArgument.getPlayer(context, "target")))))
        ));
    }

    private static int ownBalance(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int balance = RaidPointsStore.balance(player.getUUID());
        source.sendSuccess(() -> CommandFormat.header("Raid Points")
                .append(Component.literal("  " + balance + " RP").withStyle(ChatFormatting.AQUA)), false);
        // What a raid is worth, so a player can work out how far off a purchase they are without
        // asking. Cheap to print and it is the question that always follows the balance.
        CobbleRaidsConfig.RaidPoints config = CobbleRaidsConfigManager.get().raidPoints();
        if (config.enabled() && !config.isNoOp()) {
            for (RaidRarityTier tier : RaidRarityTier.values()) {
                source.sendSuccess(() -> CommandFormat.row(
                        CommandFormat.pad(tier.displayName(), 12) + config.pointsFor(tier) + " RP")
                        .withStyle(RaidTierPresentation.color(tier)), false);
            }
        }
        return balance;
    }

    private static int otherBalance(CommandSourceStack source, ServerPlayer target) {
        int balance = RaidPointsStore.balance(target.getUUID());
        source.sendSuccess(() -> CommandFormat.row(target.getGameProfile().getName() + " has "
                + balance + " RP"), false);
        return balance;
    }

    private static int give(CommandSourceStack source, ServerPlayer target, int amount) {
        int balance = RaidPointsStore.award(source.getServer(), target.getUUID(), amount);
        source.sendSuccess(() -> Component.literal("Gave " + amount + " RP to "
                + target.getGameProfile().getName() + " (now " + balance + ")")
                .withStyle(ChatFormatting.GREEN), true);
        return balance;
    }

    private static int take(CommandSourceStack source, ServerPlayer target, int amount) {
        if (!RaidPointsStore.spend(source.getServer(), target.getUUID(), amount)) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " only has "
                    + RaidPointsStore.balance(target.getUUID()) + " RP"));
            return 0;
        }
        int balance = RaidPointsStore.balance(target.getUUID());
        source.sendSuccess(() -> Component.literal("Took " + amount + " RP from "
                + target.getGameProfile().getName() + " (now " + balance + ")")
                .withStyle(ChatFormatting.GREEN), true);
        return balance;
    }

    private static int set(CommandSourceStack source, ServerPlayer target, int amount) {
        int balance = RaidPointsStore.set(source.getServer(), target.getUUID(), amount);
        source.sendSuccess(() -> Component.literal("Set " + target.getGameProfile().getName()
                + " to " + balance + " RP").withStyle(ChatFormatting.GREEN), true);
        return balance;
    }
}
