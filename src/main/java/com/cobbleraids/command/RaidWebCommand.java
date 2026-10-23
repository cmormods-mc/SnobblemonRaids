package com.cobbleraids.command;

import com.cobbleraids.web.HubWebPage;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /cobbleraids menu} opens the raid hub. */
public final class RaidWebCommand {
    private RaidWebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("menu")
                                .executes(context -> menu(context.getSource())))));
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
}
