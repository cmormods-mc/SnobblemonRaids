package com.cobbleraids.stats;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.shop.ShopResetPeriod;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The raid lifecycle's way into the statistics: one method per thing that happens in a raid.
 *
 * <p>Each call is a handful of counter updates on the in-memory book plus marking the store dirty --
 * no I/O, nothing proportional to the number of players, nothing run on a timer. The write to disk is
 * the world autosave's, as it is for every other raid store.
 *
 * <p>Callers wrap these in a fault barrier: a statistic failing to record must never be what stops a
 * raid finishing or a reward being paid.
 */
public final class RaidStats {

    private RaidStats() {}

    /** The current week, by the same Monday-UTC windows the shop's weekly limits use. */
    public static long window() {
        return ShopResetPeriod.WEEKLY.windowOf(Instant.now());
    }

    /**
     * Whether this raid is a raid for statistics. An ordinary one always is; an owned encounter
     * (another mod's boss fight) counts only if its owner left raid history switched on, the same
     * flag that governs the win record and the trophy room.
     */
    public static boolean counts(RaidSession raid) {
        return !raid.isOwned() || raid.getOwnership().policy().raidHistory();
    }

    // ---------------------------------------------------------------- raid events

    public static void onRaidStarted(MinecraftServer server, Collection<UUID> players) {
        RaidStatsStore.book().start(players, names(server), window());
        RaidStatsStore.touch(server);
    }

    public static void onVictory(MinecraftServer server, RaidSession raid) {
        if (!counts(raid)) return;
        RaidDefinition definition = RaidDefinitionRegistry.get(raid.getDefinitionId());
        if (definition == null) return;
        Set<UUID> victors = new HashSet<>(raid.getActiveParticipants());
        int seconds = raid.getElapsedCombatTicks() / 20;
        RaidStatsStore.book().victory(definition.rarityTier(), victors, damageOf(raid), seconds,
                !raid.getRenownTitle().isEmpty(), names(server), window());
        RaidStatsStore.touch(server);
    }

    public static void onLoss(MinecraftServer server, RaidSession raid) {
        if (!counts(raid)) return;
        Set<UUID> remaining = new HashSet<>(raid.getActiveParticipants());
        RaidStatsStore.book().loss(remaining, damageOf(raid), names(server), window());
        RaidStatsStore.touch(server);
    }

    /** A player withdrew, disconnected for good, or fled. */
    public static void onLeft(MinecraftServer server, RaidSession raid, UUID player) {
        if (!counts(raid)) return;
        RaidStatsStore.book().fled(player, names(server), window());
        RaidStatsStore.touch(server);
    }

    public static void onCatch(MinecraftServer server, UUID player) {
        RaidStatsStore.book().caught(player, names(server), window());
        RaidStatsStore.touch(server);
    }

    public static void onPointsEarned(MinecraftServer server, UUID player, int points) {
        RaidStatsStore.book().points(player, points, names(server), window());
        RaidStatsStore.touch(server);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<UUID, Float> damageOf(RaidSession raid) {
        return raid.getContributionSnapshot();
    }

    /** The name to store for a player: who they are right now, or what the profile cache remembers. */
    public static Function<UUID, String> names(MinecraftServer server) {
        return id -> {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getGameProfile().getName();
            return nameOf(server, id);
        };
    }

    /** A name for any player the server has seen, or empty. */
    public static String nameOf(MinecraftServer server, UUID id) {
        if (server.getProfileCache() == null) return "";
        return server.getProfileCache().get(id).map(profile -> profile.getName()).orElse("");
    }
}
