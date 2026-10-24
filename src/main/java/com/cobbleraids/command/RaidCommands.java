package com.cobbleraids.command;

import com.cobbleraids.reward.RaidRewardCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Every CobbleRaids command, registered in the one order that is safe.
 *
 * <p>Everything hangs off one shared {@code cobbleraids} root, and several classes register it.
 * ORDER MATTERS: Brigadier's {@code CommandNode.addChild} merges a same-named node by keeping the
 * FIRST-registered node's own requirement and copying only the later node's children (and command)
 * onto it. {@link RaidRewardCommand} therefore registers the root first, with no requirement on
 * purpose, so that player commands do not need operator permission just to route through it.
 * {@link RaidAdminCommand} registers later and puts the operator check on each of its own
 * subcommands, because a requirement on its root would be discarded by that same merge. Reordering
 * the calls below could silently make every player command require permission level 2, or -- worse --
 * leave an operator command open; {@code RaidCommandGatingTest} pins the result either way.
 *
 * <p>Both the live registration and that test go through {@link #buildAll}, so the test cannot
 * drift from what the server actually does.
 */
public final class RaidCommands {
    /** Short alias for {@code /cobbleraids}. Not {@code /raid}: vanilla owns that one, at level 3. */
    public static final String ALIAS = "cr";

    private RaidCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> buildAll(dispatcher));
    }

    public static void buildAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        RaidRewardCommand.build(dispatcher);   // first: owns the requirement-free root
        RaidPointsCommand.build(dispatcher);
        RaidShopCommand.build(dispatcher);
        RaidWebCommand.build(dispatcher);
        RaidStatsCommand.build(dispatcher);
        RaidTitleCommand.build(dispatcher);
        RaidAdminCommand.build(dispatcher);
        RaidLeaveCommand.build(dispatcher);
        RaidNotifyCommand.build(dispatcher);
        alias(dispatcher);                     // last: points at the finished tree
    }

    /**
     * {@code /cr} redirects to the finished {@code cobbleraids} node, so it is the same tree with the
     * same per-command permission checks, not a copy that could drift. The redirect target is read
     * after every other registration, which is why this must stay last.
     */
    private static void alias(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("cobbleraids");
        if (root == null) return;
        dispatcher.register(Commands.literal(ALIAS).executes(root.getCommand()).redirect(root));
    }
}
