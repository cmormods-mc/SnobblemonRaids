package com.cobbleraids.command;

import com.cobbleraids.web.WebUi;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /cobbleraids web hello}: opens the test page, to check a client can show WebGUI pages. */
public final class RaidWebCommand {
    private RaidWebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("web")
                                .then(Commands.literal("hello")
                                        .executes(context -> hello(context.getSource()))))));
    }

    private static int hello(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!WebUi.available()) {
            source.sendFailure(Component.literal("WebGUI is not installed on this server."));
            return 0;
        }
        if (!WebUi.open(player, "hello.html")) {
            source.sendFailure(Component.literal(
                    "Your client does not have WebGUI, so it cannot show this page."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Opening the test page.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
