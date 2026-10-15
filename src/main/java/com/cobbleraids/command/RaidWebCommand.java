package com.cobbleraids.command;

import com.cobbleraids.web.HubWebPage;
import com.cobbleraids.web.WebUi;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /cobbleraids menu} opens the raid hub; {@code /cobbleraids web hello} opens a test page to check WebGUI works. */
public final class RaidWebCommand {
    private RaidWebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("menu")
                                .executes(context -> menu(context.getSource())))
                        .then(Commands.literal("web")
                                .then(Commands.literal("hello")
                                        .executes(context -> hello(context.getSource()))))));
    }

    /** {@code /cobbleraids menu}: the raid hub, for a player who would rather type than press F6. */
    private static int menu(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!HubWebPage.open(source.getPlayerOrException())) {
            source.sendFailure(Component.literal(
                    "The raid menu needs WebGUI on your client. Try /cobbleraids shop, trophy or legends."));
            return 0;
        }
        return 1;
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
