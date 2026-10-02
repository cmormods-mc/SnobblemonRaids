package com.cobbleraids.command;

import com.cobbleraids.catching.RaidCaptureSession;
import com.cobbleraids.catching.RaidCaptureSessionService;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Support visibility and an escape hatch for the Raid Capture Protocol: seeing what session a player
 * is stuck on, and clearing it without waiting out a deadline. Mirrors {@link RaidAdminRewardOps}'
 * shape for the unrelated RP claim queue.
 *
 * <p>{@code clear} settles nothing -- neither RP nor a rolled Pokemon is granted for whatever it
 * discards, since there is no way to know which route the player would have preferred. It exists for
 * a session genuinely stuck (e.g. its definition was removed from a datapack mid-attempt), not as a
 * routine way to skip the choice.
 */
final class RaidAdminCaptureOps {
    private RaidAdminCaptureOps() {}

    /** One player's sessions, or a roster of everyone holding one when no player is given. */
    static int status(CommandSourceStack source, ServerPlayer target) {
        if (target == null) return statusAll(source);

        List<RaidCaptureSession> sessions = RaidCaptureSessionService.pendingFor(target.getUUID());
        String name = target.getGameProfile().getName();
        if (sessions.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + " has no active raid capture session.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header(name + "'s capture session(s) (" + sessions.size() + ")"), false);
        int index = 1;
        for (RaidCaptureSession session : sessions) {
            int position = index++;
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(position + ".", 4)
                            + CommandFormat.pad(session.tier().serializedName(), 11)
                            + CommandFormat.pad(session.species().getPath(), 16)
                            + CommandFormat.pad(session.phase().name(), 15)
                            + session.delivery().name())
                    .withStyle(RaidTierPresentation.color(session.tier())), false);
        }
        if (sessions.size() > 1) {
            source.sendSuccess(() -> CommandFormat.hint(" only the oldest is acted on until it resolves"), false);
        }
        return sessions.size();
    }

    private static int statusAll(CommandSourceStack source) {
        return PendingHolderCommands.listHolders(source, RaidCaptureSessionService.playersWithSessions(),
                playerId -> RaidCaptureSessionService.pendingFor(playerId).size(),
                "Nobody has an active raid capture session.", "active capture sessions");
    }

    static int clear(CommandSourceStack source, ServerPlayer target) {
        int removed = RaidCaptureSessionService.clearPending(target.getUUID(), source.getServer());
        return PendingHolderCommands.reportCleared(source, target, removed, "active raid capture session",
                "raid capture session(s)", " Neither RP nor a Pokemon was granted for them.");
    }
}
