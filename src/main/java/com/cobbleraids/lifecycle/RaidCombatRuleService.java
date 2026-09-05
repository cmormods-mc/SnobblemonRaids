package com.cobbleraids.lifecycle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-tick enforcement for raid combat rules that are external to Showdown. */
public final class RaidCombatRuleService {
    private static final ConcurrentHashMap<UUID, Set<Integer>> WARNED_SECONDS = new ConcurrentHashMap<>();
    private RaidCombatRuleService() {}

    public static void tick(MinecraftServer server) {
        for (RaidSession raid : RaidRegistry.all()) {
            if (raid.getStatus() != RaidSession.Status.ACTIVE || !raid.isTimed()) continue;
            boolean expired = raid.tickCombatTimer();
            int remainingTicks = raid.getRemainingCombatTicks();
            if (!expired) {
                int seconds = (remainingTicks + 19) / 20;
                if (seconds == 60 || seconds == 30 || seconds == 10 || (seconds <= 5 && seconds > 0)) {
                    Set<Integer> warned = WARNED_SECONDS.computeIfAbsent(raid.getId(), ignored -> ConcurrentHashMap.newKeySet());
                    if (warned.add(seconds)) broadcast(server, raid,
                            Component.literal("Raid time remaining: " + seconds + "s").withStyle(ChatFormatting.YELLOW));
                }
                continue;
            }
            WARNED_SECONDS.remove(raid.getId());
            RaidLifecycleCoordinator.timeout(raid, server);
        }
    }

    public static void forget(UUID raidId) {
        if (raidId != null) WARNED_SECONDS.remove(raidId);
    }

    private static void broadcast(MinecraftServer server, RaidSession raid, Component message) {
        for (UUID playerId : raid.getActiveParticipants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.sendSystemMessage(message);
        }
    }
}
