package com.cobbleraids.command;

import com.cobbleraids.catching.LegendGalleryQuery;
import com.cobbleraids.catching.LegendRoomGateway;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Opens the server's Hall of Legends. See {@link LegendRoomGateway} for what it shows. */
public final class RaidLegendsCommand {
    private RaidLegendsCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("legends")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    // Same reasoning as RaidTrophyCommand: a reasonable starting guess,
                                    // corrected as soon as the client's own layout runs.
                                    LegendRoomGateway.open(player, LegendGalleryQuery.MAX_COLUMNS);
                                    return 1;
                                }))
        ));
    }
}
