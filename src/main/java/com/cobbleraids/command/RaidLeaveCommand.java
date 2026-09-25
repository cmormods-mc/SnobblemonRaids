package com.cobbleraids.command;

import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-facing escape hatch, independent of a raid's own {@code allow_flee}: that flag governs the
 * in-battle Run button mid-fight, which is a different intent from a deliberate exit. Reuses
 * {@link RaidLifecycleCoordinator#withdrawPlayer}, the exact same forfeit-and-notify path the Run
 * button already takes when flee is allowed, just without its {@code isFleeAllowed()} gate.
 *
 * <p>Exists because reconnect grace ({@code RaidReconnectService}) now holds a disconnected player's
 * slot for several minutes instead of freeing it immediately, and a raid with flee disabled had no
 * other way out at all.
 */
public final class RaidLeaveCommand {
    private RaidLeaveCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("leave")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    PokemonBattle battle = Cobblemon.INSTANCE.getBattleRegistry()
                                            .getBattleByParticipatingPlayer(player);
                                    RaidSession raid = RaidRegistry.get(battle);
                                    if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE
                                            || !raid.isActiveParticipant(player.getUUID())) {
                                        player.sendSystemMessage(Component.literal("You are not in an active raid.")
                                                .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    return RaidLifecycleCoordinator.withdrawPlayer(raid, player) ? 1 : 0;
                                }))
        ));
    }
}
