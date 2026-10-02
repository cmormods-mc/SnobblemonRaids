package com.cobbleraids.command;

import com.cobbleraids.presentation.CommandFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two admin commands every "queue a player is holding" feature needs: a roster of who holds
 * something, and a way to drop one player's holdings. Unclaimed rewards and capture sessions each
 * had their own copy of both; only the wording differed, so the wording is what is passed in.
 *
 * <p>Offline holders are listed by id rather than skipped. They are exactly who these commands exist
 * to find -- someone stuck while away -- and that id still works as the player argument to the
 * matching clear command.
 */
final class PendingHolderCommands {
    private PendingHolderCommands() {}

    /**
     * Lists everyone in {@code holders} with how many items each holds.
     *
     * @param noneMessage the whole line printed when nobody holds anything
     * @param noun        what the header counts, e.g. "unclaimed rewards" -- printed as "Players with
     *                    unclaimed rewards (3)"
     */
    static int listHolders(CommandSourceStack source, Set<UUID> holders, ToIntFunction<UUID> countOf,
                           String noneMessage, String noun) {
        if (holders.isEmpty()) {
            source.sendSuccess(() -> Component.literal(noneMessage).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header("Players with " + noun + " (" + holders.size() + ")"), false);
        for (UUID playerId : holders) {
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
            String label = online != null ? online.getGameProfile().getName() : playerId.toString();
            int count = countOf.applyAsInt(playerId);
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(label, 38) + count
                    + (online == null ? "  (offline)" : "")), false);
        }
        return holders.size();
    }

    /**
     * Reports the outcome of dropping one player's holdings, after the caller has done it.
     *
     * @param removed       how many the caller removed
     * @param noneWhat      what the player had none of, printed as "NAME had no &lt;noneWhat&gt;."
     * @param clearedNoun   what was cleared, printed as "Cleared N &lt;clearedNoun&gt; from NAME."
     * @param afterClearing anything to append once it is cleared, with its own leading space, or ""
     */
    static int reportCleared(CommandSourceStack source, ServerPlayer target, int removed,
                             String noneWhat, String clearedNoun, String afterClearing) {
        String name = target.getGameProfile().getName();
        if (removed == 0) {
            source.sendSuccess(() -> Component.literal(name + " had no " + noneWhat + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cleared " + removed + " " + clearedNoun + " from "
                + name + "." + afterClearing).withStyle(ChatFormatting.GREEN), true);
        return removed;
    }
}
