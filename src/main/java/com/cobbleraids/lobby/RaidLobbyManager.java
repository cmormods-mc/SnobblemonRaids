package com.cobbleraids.lobby;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.raid.RaidFactory;
import com.cobbleraids.raid.RaidScalingPolicy;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread coordinator for wild-boss recruitment windows. */
public final class RaidLobbyManager {
    private static final Map<UUID, RaidLobby> BY_BOSS = new ConcurrentHashMap<>();
    private RaidLobbyManager() {}

    public enum JoinResult { STARTED_RECRUITMENT, JOINED, ALREADY_JOINED, FULL, TOO_FAR, UNAVAILABLE, NOT_A_RAID_BOSS }

    public static JoinResult interact(ServerPlayer player, PokemonEntity boss) {
        if (!RaidBossEntityMarker.isRaidBoss(boss)) return JoinResult.NOT_A_RAID_BOSS;
        if (boss.isRemoved() || boss.isBattling()) return JoinResult.UNAVAILABLE;

        RaidDefinition definition = RaidBossEntityMarker.definitionId(boss).map(RaidDefinitionRegistry::get).orElse(null);
        if (definition == null) {
            player.sendSystemMessage(Component.literal("This raid boss has no loaded raid definition.").withStyle(ChatFormatting.RED));
            return JoinResult.UNAVAILABLE;
        }
        if (!isWithinRecruitmentRadius(player, boss, definition.recruitment().radius())) {
            player.sendSystemMessage(Component.literal("Move closer to the raid boss to join.").withStyle(ChatFormatting.RED));
            return JoinResult.TOO_FAR;
        }

        long now = boss.level().getGameTime();
        RaidLobby lobby = BY_BOSS.get(boss.getUUID());
        boolean created = false;
        if (lobby == null || lobby.status() == RaidLobby.Status.CANCELLED || lobby.status() == RaidLobby.Status.STARTED) {
            lobby = new RaidLobby(boss, definition, now);
            BY_BOSS.put(boss.getUUID(), lobby);
            created = true;
        }

        if (lobby.isOptedIn(player.getUUID())) {
            long seconds = Math.max(0L, (lobby.closesAtTick() - now + 19L) / 20L);
            player.sendSystemMessage(Component.literal("You are already in this raid. Starts in " + seconds + "s."));
            return JoinResult.ALREADY_JOINED;
        }
        if (lobby.joinedCount() >= definition.recruitment().maxPlayers()) {
            player.sendSystemMessage(Component.literal("This raid lobby is full.").withStyle(ChatFormatting.RED));
            return JoinResult.FULL;
        }
        if (!lobby.join(player.getUUID())) return JoinResult.UNAVAILABLE;

        long seconds = Math.max(0L, (lobby.closesAtTick() - now + 19L) / 20L);
        player.sendSystemMessage(Component.literal("Joined raid: " + lobby.joinedCount() + "/" + definition.recruitment().maxPlayers()
                + " players. Starts in " + seconds + "s.").withStyle(ChatFormatting.GOLD));
        broadcastNearby(lobby, Component.literal(player.getGameProfile().getName() + " joined the raid ("
                + lobby.joinedCount() + "/" + definition.recruitment().maxPlayers() + ").").withStyle(ChatFormatting.YELLOW));
        return created ? JoinResult.STARTED_RECRUITMENT : JoinResult.JOINED;
    }

    public static void tick(MinecraftServer server) {
        // Empty on almost every tick outside an active recruitment window; skip the defensive
        // copy entirely rather than allocating one 20x/second at idle.
        if (BY_BOSS.isEmpty()) return;
        for (RaidLobby lobby : List.copyOf(BY_BOSS.values())) {
            if (lobby.status() != RaidLobby.Status.RECRUITING) continue;
            PokemonEntity boss = lobby.boss();
            if (boss.isRemoved() || !RaidBossEntityMarker.isRaidBoss(boss)) {
                lobby.cancel();
                BY_BOSS.remove(boss.getUUID(), lobby);
                continue;
            }

            long now = boss.level().getGameTime();
            long remainingTicks = lobby.closesAtTick() - now;
            if (remainingTicks > 0) {
                // Lightweight countdown: broadcast at 30, 20, 10, 5, 4, 3, 2, 1 seconds if those values occur.
                if (remainingTicks % 20L == 0L) {
                    long seconds = remainingTicks / 20L;
                    if (seconds == 30 || seconds == 20 || seconds == 10 || seconds <= 5) {
                        broadcastNearby(lobby, Component.literal("Raid starts in " + seconds + "s — right-click the boss to join.")
                                .withStyle(ChatFormatting.GOLD));
                    }
                }
                continue;
            }
            freezeAndStart(server, lobby);
        }
    }

    private static void freezeAndStart(MinecraftServer server, RaidLobby lobby) {
        lobby.starting();
        PokemonEntity boss = lobby.boss();
        RaidDefinition definition = lobby.definition();
        List<ServerPlayer> eligible = new ArrayList<>();
        for (UUID playerId : lobby.optedIn()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !isEligibleAtLock(player, boss, definition)) continue;
            eligible.add(player);
        }

        if (eligible.isEmpty()) {
            lobby.cancel();
            BY_BOSS.remove(boss.getUUID(), lobby);
            broadcastNearby(lobby, Component.literal("Raid cancelled: no eligible players remained nearby.").withStyle(ChatFormatting.RED));
            return;
        }

        long scaledHealth = RaidScalingPolicy.maxHealth(definition, eligible.size());
        try {
            RaidFactory.startFromWildBoss(eligible, definition, boss, scaledHealth);
            lobby.started();
            BY_BOSS.remove(boss.getUUID(), lobby);
        } catch (RuntimeException ex) {
            lobby.cancel();
            BY_BOSS.remove(boss.getUUID(), lobby);
            broadcastNearby(lobby, Component.literal("Raid could not start; the boss remains available.").withStyle(ChatFormatting.RED));
            System.err.println("[CobbleRaids] Failed to start raid " + definition.id() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static boolean isEligibleAtLock(ServerPlayer player, PokemonEntity boss, RaidDefinition definition) {
        if (!player.isAlive() || player.isSpectator()) return false;
        if (player.level() != boss.level()) return false;
        if (!isWithinRecruitmentRadius(player, boss, definition.recruitment().radius())) return false;
        if (Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null) return false;
        return !Cobblemon.INSTANCE.getStorage().getParty(player).toBattleTeam(true, false).isEmpty();
    }

    private static boolean isWithinRecruitmentRadius(ServerPlayer player, PokemonEntity boss, double radius) {
        return player.level() == boss.level() && player.distanceToSqr(boss) <= radius * radius;
    }

    private static void broadcastNearby(RaidLobby lobby, Component message) {
        PokemonEntity boss = lobby.boss();
        double radius = Math.max(16.0, lobby.definition().recruitment().radius() * 2.0);
        for (ServerPlayer player : ((ServerLevel) boss.level()).players()) {
            if (player.distanceToSqr(boss) <= radius * radius) player.sendSystemMessage(message);
        }
    }


    /** Snapshot used by admin/debug commands; lobby mutation remains server-thread owned. */
    public static List<RaidLobby> all() { return List.copyOf(BY_BOSS.values()); }

    /** Cancels and forgets recruitment for a boss before an administrative despawn. */
    public static boolean cancelForBoss(PokemonEntity boss) {
        if (boss == null) return false;
        RaidLobby lobby = BY_BOSS.remove(boss.getUUID());
        if (lobby == null) return false;
        lobby.cancel();
        return true;
    }

    public static RaidLobby get(PokemonEntity boss) { return boss == null ? null : BY_BOSS.get(boss.getUUID()); }

    public static boolean hasActiveLobby(PokemonEntity boss) {
        RaidLobby lobby = get(boss);
        if (lobby == null) return false;
        RaidLobby.Status status = lobby.status();
        return status == RaidLobby.Status.RECRUITING || status == RaidLobby.Status.STARTING;
    }
}
