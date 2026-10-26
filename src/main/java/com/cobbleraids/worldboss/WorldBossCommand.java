package com.cobbleraids.worldboss;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /cobbleraids worldboss ...}: players join, leave and check the fight; operators start,
 * stop and reload it.
 *
 * <p>Registered under the shared {@code cobbleraids} root by
 * {@link com.cobbleraids.command.RaidCommands}, which explains why each operator subcommand carries
 * its own permission check: a requirement on a shared root would be discarded when same-named
 * nodes merge.
 */
public final class WorldBossCommand {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    private WorldBossCommand() {}

    public static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbleraids")
                .then(Commands.literal("worldboss")
                        .then(Commands.literal("join").executes(WorldBossCommand::join))
                        .then(Commands.literal("leave").executes(WorldBossCommand::leave))
                        .then(Commands.literal("status").executes(WorldBossCommand::status))
                        .then(Commands.literal("me").executes(WorldBossCommand::me))
                        .then(Commands.literal("start")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .executes(WorldBossCommand::start))
                        .then(Commands.literal("stop")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .executes(WorldBossCommand::stop))
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .executes(WorldBossCommand::reload))));
    }

    private static int join(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String problem = WorldBossService.join(player);
        if (problem != null) {
            player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean was = WorldBossService.leave(player);
        player.sendSystemMessage(Component.literal(was
                        ? "You will not start another duel by yourself. Your damage still counts."
                        : "You were not waiting for another duel.")
                .withStyle(ChatFormatting.YELLOW));
        return was ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        for (String line : WorldBossService.status()) {
            ctx.getSource().sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int me(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String share = WorldBossService.myShare(player.getUUID());
        player.sendSystemMessage(Component.literal(share == null
                        ? "There is no World Boss right now." : "You have dealt " + share + ".")
                .withStyle(ChatFormatting.YELLOW));
        return share == null ? 0 : 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer near = ctx.getSource().getPlayer();
        String problem = WorldBossService.start(ctx.getSource().getServer(), near);
        if (problem != null) {
            ctx.getSource().sendFailure(Component.literal("Could not start the World Boss: " + problem));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("World Boss started."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        boolean stopped = WorldBossService.stop(ctx.getSource().getServer());
        if (!stopped) {
            ctx.getSource().sendFailure(Component.literal("No World Boss is active."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("World Boss stopped."), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            WorldBossConfig config = WorldBossConfigManager.reload();
            ctx.getSource().sendSuccess(() -> Component.literal("World Boss config reloaded (enabled="
                    + config.enabled() + ")."), true);
            return 1;
        } catch (RuntimeException ex) {
            ctx.getSource().sendFailure(Component.literal("World Boss config not reloaded: " + ex.getMessage()));
            return 0;
        }
    }
}
