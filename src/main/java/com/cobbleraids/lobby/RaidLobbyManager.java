package com.cobbleraids.lobby;

import com.cobbleraids.presentation.RaidBroadcast;
import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.raid.RaidFactory;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.renown.RaidRenownMarker;
import com.cobbleraids.renown.RenownBoon;
import com.cobbleraids.raid.RaidLevelPolicy;
import com.cobbleraids.raid.RaidScalingPolicy;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
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
    /** Headroom past the recruitment window so a raid that just locked still has time to be fought. */
    private static final int LOBBY_EXPIRY_MARGIN_SECONDS = 30;
    private RaidLobbyManager() {}

    public enum JoinResult { STARTED_RECRUITMENT, JOINED, ALREADY_JOINED, FULL, TOO_FAR, UNAVAILABLE, NOT_A_RAID_BOSS, OWNED_ENCOUNTER }

    public static JoinResult interact(ServerPlayer player, PokemonEntity boss) {
        if (!RaidBossEntityMarker.isRaidBoss(boss)) return JoinResult.NOT_A_RAID_BOSS;
        // Its participants were chosen by the mod that owns it; nobody else can recruit into it.
        if (RaidBossEntityMarker.isOwned(boss)) {
            player.sendSystemMessage(Component.literal("This boss belongs to another encounter and cannot be joined.")
                    .withStyle(ChatFormatting.RED));
            return JoinResult.OWNED_ENCOUNTER;
        }
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
        if (lobby == null || lobby.status() == RaidRecruitmentRoster.Status.CANCELLED || lobby.status() == RaidRecruitmentRoster.Status.STARTED) {
            // A wild boss has a total lifetime cap that recruitment does not extend, so refuse to
            // open a lobby that could not finish recruiting before the boss is due to leave. Without
            // this, players would join, wait out the countdown, and watch the boss vanish at lock.
            // -1 means the scheduler does not track it (admin-spawned), which has no cap.
            long secondsLeft = RaidSpawnScheduler.secondsUntilExpiry(boss.getUUID());
            if (secondsLeft >= 0L && secondsLeft < definition.recruitment().durationSeconds() + LOBBY_EXPIRY_MARGIN_SECONDS) {
                player.sendSystemMessage(Component.literal("This raid boss is about to leave; it cannot start a new raid.")
                        .withStyle(ChatFormatting.RED));
                return JoinResult.UNAVAILABLE;
            }
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
            if (lobby.status() != RaidRecruitmentRoster.Status.RECRUITING) continue;
            PokemonEntity boss = lobby.boss();
            if (boss.isRemoved() || !RaidBossEntityMarker.isRaidBoss(boss)) {
                lobby.cancel();
                BY_BOSS.remove(boss.getUUID(), lobby);
                continue;
            }

            long now = boss.level().getGameTime();
            long remainingTicks = lobby.ticksRemaining(now);
            if (!lobby.hasClosed(now)) {
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

        // Level first: the health pool is scaled by the level the boss ends up at, so a party that
        // raises a boss also raises what it has to chew through.
        int bossLevel = applyDynamicLevel(boss, definition, eligible);
        long scaledHealth = withRenownHealth(boss, RaidScalingPolicy.maxHealth(definition, eligible.size(), bossLevel));
        try {
            RaidFactory.startFromWildBoss(eligible, definition, boss, scaledHealth);
            lobby.started();
            BY_BOSS.remove(boss.getUUID(), lobby);
        } catch (RuntimeException ex) {
            lobby.cancel();
            BY_BOSS.remove(boss.getUUID(), lobby);
            broadcastNearby(lobby, Component.literal("Raid could not start; the boss remains available.").withStyle(ChatFormatting.RED));
            RaidLog.error("Failed to start raid {}", definition.id(), ex);
        }
    }

    /**
     * Raises the boss to meet the group, once, at the moment recruitment locks.
     *
     * <p>Here rather than at spawn because the boss appears before anybody joins: the scheduler
     * picks one nearby player and the lobby then recruits up to four, so a spawn-time reading is
     * not the group that fights. This is also where max HP has always been decided, for the same
     * reason.
     *
     * <p>Guarded and silent on failure. A level that cannot be read must not stop a raid starting
     * -- the boss simply fights at the level its definition asks for, which is what it did before
     * any of this existed.
     */
    private static int applyDynamicLevel(PokemonEntity boss, RaidDefinition definition,
                                         List<ServerPlayer> eligible) {
        // Falls back to the definition's own level, which is what the health pool is sized against
        // when nothing scales -- so a failure here cannot produce a mismatched pool either.
        int[] applied = { definition.level() };
        RaidFaultBarrier.guard("lobby:dynamic-level", () -> {
            CobbleRaidsConfig.DynamicLevel config = CobbleRaidsConfigManager.get().dynamicLevel();
            if (!config.enabled()) return;

            List<Integer> levels = new ArrayList<>();
            for (ServerPlayer player : eligible) {
                for (BattlePokemon member : Cobblemon.INSTANCE.getStorage().getParty(player).toBattleTeam(true, false)) {
                    levels.add(member.getEffectedPokemon().getLevel());
                }
            }

            double average = RaidLevelPolicy.average(levels);
            int level = RaidLevelPolicy.bossLevel(definition.level(), average, config);
            String party = String.format(java.util.Locale.ROOT, "%.1f", average);

            if (level == boss.getPokemon().getLevel()) {
                // Logged too. Without this there is no way to tell "decided not to scale" from
                // "never ran": the common case is a party averaging below the definition, where
                // the floor wins and nothing visibly happens, and that is exactly the case an
                // operator wonders about after enabling the feature.
                RaidLog.info("{} stays at level {}: {} player(s) averaging {} across {} Pokemon",
                        definition.id(), level, eligible.size(), party, levels.size());
                applied[0] = level;
                return;
            }
            applied[0] = level;

            boss.getPokemon().setLevel(level);
            // Level changes max HP, and a boss left on its old current health would enter the
            // battle already damaged -- the same ordering RaidBossSpawner documents.
            boss.getPokemon().setCurrentHealth(boss.getPokemon().getMaxHealth());
            // Put the level on the nameplate, but only now that it is not the one the definition
            // advertises. Cobblemon already draws a level on its own entity label, so saying it
            // again on an unscaled boss would be pure duplication -- whereas a boss that has been
            // raised to meet the party is the one case where the number is worth stating outright.
            // Through RaidBossNameplate, which also carries a renowned boss's title -- rebuilding the
            // name from the species here used to be the one place that title would have been lost.
            boss.setCustomName(RaidBossNameplate.of(definition.rarityTier(),
                    boss.getPokemon().getSpecies().getTranslatedName(),
                    RaidRenownMarker.read(boss).orElse(null), level));
            boss.setCustomNameVisible(true);

            RaidLog.info("{} scaled from level {} to {}: {} player(s) averaging {} across {} Pokemon",
                    definition.id(), definition.level(), level, eligible.size(), party, levels.size());
        });
        return applied[0];
    }

    /**
     * Enlarges the pool of a renowned boss whose epithet carries the hp_pool boon.
     *
     * <p>Guarded like the level: a boon that cannot be read leaves an ordinary fight, never a raid
     * that fails to start.
     */
    private static long withRenownHealth(PokemonEntity boss, long pool) {
        long[] result = { pool };
        RaidFaultBarrier.guard("lobby:renown-health", () -> RaidRenownMarker.read(boss)
                .filter(renown -> renown.boon().kind() == RenownBoon.Kind.HP_POOL)
                .ifPresent(renown -> result[0] = RaidScalingPolicy.forRenown(pool,
                        CobbleRaidsConfigManager.get().renown().healthBonus())));
        return result[0];
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

    /**
     * Twice the recruitment radius, and never less than 16 blocks: someone standing just outside the
     * radius should still hear the countdown and get the chance to walk in.
     */
    private static void broadcastNearby(RaidLobby lobby, Component message) {
        double radius = Math.max(16.0, lobby.definition().recruitment().radius() * 2.0);
        RaidBroadcast.near(lobby.boss(), radius, message);
    }


    /** Snapshot used by admin/debug commands; lobby mutation remains server-thread owned. */
    public static List<RaidLobby> all() { return List.copyOf(BY_BOSS.values()); }

    /**
     * Drops every lobby once the server is gone. A RaidLobby holds its boss PokemonEntity, and an
     * entity reaches its ServerLevel, so a lobby left here after a world closes pins that entire
     * world in memory -- and an integrated (single-player) client reuses this JVM for every world
     * it opens. tick() only ever revisits RECRUITING lobbies, so anything left in another status
     * would never be reclaimed on its own.
     */
    public static int onServerStopped() {
        int dropped = BY_BOSS.size();
        BY_BOSS.clear();
        return dropped;
    }

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
        RaidRecruitmentRoster.Status status = lobby.status();
        return status == RaidRecruitmentRoster.Status.RECRUITING || status == RaidRecruitmentRoster.Status.STARTING;
    }
}
