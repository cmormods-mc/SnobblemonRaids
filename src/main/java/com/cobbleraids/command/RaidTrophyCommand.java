package com.cobbleraids.command;

import com.cobbleraids.catching.TrophyRoomGateway;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Opens a player's trophy room. See {@link TrophyRoomGateway} for what it shows. */
public final class RaidTrophyCommand {
    private RaidTrophyCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("trophies")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    TrophyRoomGateway.open(player);
                                    return 1;
                                }))
        ));
    }
}
