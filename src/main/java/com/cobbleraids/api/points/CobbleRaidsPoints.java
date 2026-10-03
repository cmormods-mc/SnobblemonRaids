package com.cobbleraids.api.points;

import com.cobbleraids.reward.points.RaidPointsStore;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * Lets another mod read and credit a player's Raid Points.
 *
 * <p>Raid Points are CobbleRaids' own currency, earned by winning raids and spent in its shop. This is
 * the small, deliberate surface another mod needs to pay them out as a reward -- a tower, a quest line --
 * without reaching into CobbleRaids' record store. It credits and reads; it does not spend, because
 * spending is the shop's business and a caller that could debit a balance could also steal one.
 *
 * <p>Signatures use only {@code java.*} and {@code net.minecraft.*}, which a build-time check enforces,
 * the same rule as {@code com.cobbleraids.api.encounter}.
 *
 * <p>{@link #award} must be called on the server thread.
 */
public final class CobbleRaidsPoints {

    /** Incremented on any incompatible change to this package. */
    public static final int API_VERSION = 1;

    private CobbleRaidsPoints() {}

    /** What the player has. Zero for somebody who has never raided. */
    public static int balance(UUID playerId) {
        return RaidPointsStore.balance(playerId);
    }

    /**
     * Credits {@code amount} Raid Points and returns the new balance. A non-positive amount changes
     * nothing and returns the current balance.
     */
    public static int award(MinecraftServer server, UUID playerId, int amount) {
        return RaidPointsStore.award(server, playerId, amount);
    }
}
