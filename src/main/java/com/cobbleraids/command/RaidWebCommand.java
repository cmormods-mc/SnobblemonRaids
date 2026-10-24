package com.cobbleraids.command;

import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.web.HubWebPage;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Bare {@code /cobbleraids} (or {@code /cr}) opens the raid hub: shop, trophies, Hall of Legends and
 * leaderboards behind one command. For a player who would rather type than press F6.
 *
 * <p>The root has no permission requirement and must not gain one: every player-facing command hangs
 * off it. Operator subcommands carry their own check; see {@link RaidAdminCommand}.
 */
public final class RaidWebCommand {
    private RaidWebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> build(dispatcher));
    }

    /** The command tree, separate from the Fabric event so a test can build it without a server. */
    public static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobbleraids")
                .executes(context -> menu(context.getSource())));
    }

    private static int menu(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (HubWebPage.open(source.getPlayerOrException())) return 1;
        // No WebGUI on this client: the same things are reachable by typing, so say which.
        source.sendSuccess(() -> CommandFormat.header("CobbleRaids"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr shop      what you can buy; /cr shop buy <id>"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr points    your Raid Points"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr reward    open or claim a pending reward"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr top       leaderboards; /cr stats for yours"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr title     your titles"), false);
        source.sendSuccess(() -> CommandFormat.row("/cr leave     withdraw from a raid"), false);
        return 0;
    }
}
