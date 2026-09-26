package com.cobbleraids.command;

import com.cobbleraids.spawn.RaidSpawnAnnouncementService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets a player opt out of the one CobbleRaids message that is not scoped to anyone in particular:
 * a natural raid's server-wide spawn announcement is deliberately sent to every online player (see
 * {@code RaidSpawnAnnouncementService.naturalSpawn}), which on a server running a high
 * {@code max_active_raids} can mean several of these a minute for a player who has already seen
 * enough of them. Everything else CobbleRaids says is already scoped to a radius around a boss or
 * to the player it is actually about, so nothing else needs this.
 */
public final class RaidNotifyCommand {
    private RaidNotifyCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("notify")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    boolean muted = RaidSpawnAnnouncementService.toggleMute(player);
                                    player.sendSystemMessage(muted
                                            ? Component.literal("You will no longer see wild raid spawn"
                                                    + " announcements. Run this again to turn them back on.")
                                                    .withStyle(ChatFormatting.YELLOW)
                                            : Component.literal("You will see wild raid spawn announcements again.")
                                                    .withStyle(ChatFormatting.GREEN));
                                    return 1;
                                }))
        ));
    }
}
