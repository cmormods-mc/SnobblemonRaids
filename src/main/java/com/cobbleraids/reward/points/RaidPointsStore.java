package com.cobbleraids.reward.points;

import com.cobbleraids.catching.RaidPlayerRecords;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Raid Points: the currency raids pay, and the one the raid shop will spend.
 *
 * <p>Owned by this mod rather than borrowed from an economy mod, which is the whole point of it.
 * CobbleDollars buys everything on a server; points that only raids grant and only the raid shop
 * takes cannot be earned another way, so what a raid is worth stays a decision this mod makes. The
 * CobbleDollars path is still there and still works -- it is simply off by default now.
 *
 * <p>This is the entire surface the shop needs: read a balance, take from it, and be told no when
 * the balance is short. Kept deliberately small, because a shop that reaches past it into the
 * record store is a shop that can spend points nobody has.
 */
public final class RaidPointsStore {

    private RaidPointsStore() {}

    /** What a player has. Zero for somebody who has never raided. */
    public static int balance(UUID playerId) {
        return RaidPlayerRecords.get(playerId).raidPoints();
    }

    /** Credits points and returns the new balance. Non-positive amounts do nothing. */
    public static int award(MinecraftServer server, UUID playerId, int amount) {
        if (amount <= 0) return balance(playerId);
        return RaidPlayerRecords.addPoints(server, playerId, amount);
    }

    /**
     * Takes points if the player has them.
     *
     * <p>Returns false and takes nothing when the balance is short, rather than clamping to zero:
     * a shop needs to know whether the purchase happened, and a partial charge for a whole item is
     * the worst of both.
     */
    public static boolean spend(MinecraftServer server, UUID playerId, int amount) {
        if (amount <= 0) return true;
        if (balance(playerId) < amount) return false;
        RaidPlayerRecords.addPoints(server, playerId, -amount);
        return true;
    }

    /** Sets a balance outright, for administration. Negative values become zero. */
    public static int set(MinecraftServer server, UUID playerId, int amount) {
        return RaidPlayerRecords.addPoints(server, playerId, Math.max(0, amount) - balance(playerId));
    }
}
