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
                        .then(Commands.literal("warp").executes(WorldBossCommand::warp))
                        .then(Commands.literal("return").executes(WorldBossCommand::returnHome))
                        .then(Commands.literal("arena")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .then(Commands.literal("list").executes(WorldBossCommand::arenaList))
                                .then(Commands.literal("clear").executes(WorldBossCommand::arenaClear))
                                .then(Commands.literal("scan").executes(ctx -> arenaScan(ctx, 64))
                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8, 128))
                                                .executes(ctx -> arenaScan(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"))))))
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

    private static int warp(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String problem = WorldBossService.warp(player);
        if (problem != null) {
            player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }

    private static int returnHome(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String problem = WorldBossService.returnHome(player);
        if (problem != null) {
            player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }

    private static int arenaList(CommandContext<CommandSourceStack> ctx) {
        var markers = com.cobbleraids.worldboss.arena.ArenaMarkers.get(ctx.getSource().getServer());
        for (var kind : com.cobbleraids.worldboss.arena.ArenaMarkers.Kind.values()) {
            var spots = markers.list(kind);
            ctx.getSource().sendSuccess(() -> Component.literal(kind.name().toLowerCase() + " markers: " + spots.size()).withStyle(ChatFormatting.YELLOW), false);
            for (var spot : spots) {
                ctx.getSource().sendSuccess(() -> Component.literal("  " + spot.dimension() + " " + spot.pos().getX() + " " + spot.pos().getY() + " "
                        + spot.pos().getZ()), false);
            }
        }
        return markers.list(com.cobbleraids.worldboss.arena.ArenaMarkers.Kind.SPAWN).size();
    }

    private static int arenaClear(CommandContext<CommandSourceStack> ctx) {
        var markers = com.cobbleraids.worldboss.arena.ArenaMarkers.get(ctx.getSource().getServer());
        markers.clear(com.cobbleraids.worldboss.arena.ArenaMarkers.Kind.SPAWN);
        markers.clear(com.cobbleraids.worldboss.arena.ArenaMarkers.Kind.ENTRANCE);
        markers.flush(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Forgot every arena marker (the blocks stay where they are; /cobbleraids worldboss arena scan finds them again).")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    /** Finds marker blocks that arrived without being placed by hand (a pasted schematic) in the loaded chunks round the operator and registers them. */
    private static int arenaScan(CommandContext<CommandSourceStack> ctx, int radius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var level = player.serverLevel();
        var markers = com.cobbleraids.worldboss.arena.ArenaMarkers.get(ctx.getSource().getServer());
        String dimension = level.dimension().location().toString();
        int minY = Math.max(level.getMinBuildHeight(), player.getBlockY() - 96), maxY = Math.min(level.getMaxBuildHeight() - 1, player.getBlockY() + 96);
        int added = 0;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = player.getBlockX() - radius; x <= player.getBlockX() + radius; x++) {
            for (int z = player.getBlockZ() - radius; z <= player.getBlockZ() + radius; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).getBlock() instanceof com.cobbleraids.worldboss.arena.ArenaMarkerBlock marker
                            && markers.add(marker.kind(), new com.cobbleraids.worldboss.arena.ArenaMarkers.Spot(dimension, pos.immutable()))) added++;
                }
            }
        }
        if (added > 0) markers.flush(ctx.getSource().getServer());
        int found = added;
        ctx.getSource().sendSuccess(() -> Component.literal("Scan done: " + found + " new marker(s) registered (loaded chunks within " + radius + " blocks).")
                .withStyle(ChatFormatting.YELLOW), true);
        return found;
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
        var result = WorldBossService.stop(ctx.getSource().getServer());
        if (!result.wasActive() && result.swept() == 0) {
            ctx.getSource().sendFailure(Component.literal("No World Boss is active, and no leftover bosses stand at its last spot."));
            return 0;
        }
        String swept = result.swept() == 0 ? "" : " Removed " + result.swept() + " leftover boss(es) at its spot.";
        String message = (result.wasActive() ? "World Boss stopped." : "No World Boss was active.") + swept;
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            WorldBossConfig config = WorldBossConfigManager.reload();
            WorldBossService.refreshVisuals(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal("World Boss config reloaded (enabled="
                    + config.enabled() + ")."), true);
            return 1;
        } catch (RuntimeException ex) {
            ctx.getSource().sendFailure(Component.literal("World Boss config not reloaded: " + ex.getMessage()));
            return 0;
        }
    }
}
