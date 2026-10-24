package com.cobbleraids.shop;

import com.cobbleraids.catching.RaidPlayerRecords;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * The live rotating page: which listings a player sees right now, and buying one.
 *
 * <p>Every player has their own page, rolled from the window and their UUID, so it is the same on
 * every open and after a restart but different from the next player's. Rolling is a pure function,
 * so the cache below is an optimisation only: a dropped cache reproduces the same listings.
 *
 * <p>The cache holds one roll per player for the current window and is emptied the moment the window
 * turns over, so it can never hold more than the players who opened the shop in the last four hours.
 */
public final class ShopRotationService {

    private record Cached(ShopRotationConfig config, List<ShopRotation.Listing> listings) {}

    /** The window {@link #cache} was filled for; a different window empties it. */
    private static volatile long cachedWindow = Long.MIN_VALUE;
    private static final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    /** The roll with no player folded in, for a console or a caller with nobody in particular. */
    private static final UUID NO_PLAYER = new UUID(0L, 0L);

    private ShopRotationService() {}

    public static void invalidate() {
        cache.clear();
        cachedWindow = Long.MIN_VALUE;
        ShopRotationPool.invalidate();
    }

    public static long windowNow(Instant now) {
        return ShopResetPeriod.ROTATION.windowOf(now);
    }

    /** The reference (unsalted) listings; empty when the rotation is switched off. */
    public static List<ShopRotation.Listing> current(Instant now) {
        return current(now, null);
    }

    /** The listings this player sees at {@code now}; null player is the reference roll. */
    public static List<ShopRotation.Listing> current(Instant now, UUID player) {
        ShopRotationConfig config = ShopCatalogManager.get().rotation();
        if (!config.enabled()) return List.of();
        long window = windowNow(now);
        if (cachedWindow != window) {
            synchronized (ShopRotationService.class) {
                if (cachedWindow != window) {
                    cache.clear();
                    cachedWindow = window;
                }
            }
        }
        UUID key = player == null ? NO_PLAYER : player;
        Cached snapshot = cache.get(key);
        if (snapshot != null && snapshot.config().equals(config)) return snapshot.listings();
        List<ShopRotation.Listing> listings = ShopRotation.roll(window, ShopRotation.saltOf(player),
                ShopRotationPool.get(config.excludedLabels(), config.rarity().rareLabels()), config);
        cache.put(key, new Cached(config, listings));
        return listings;
    }

    /** When the current listings rotate out, for the page to say. The same moment for everyone. */
    public static Instant nextRotation(Instant now) {
        return Instant.ofEpochSecond((windowNow(now) + 1) * ShopResetPeriod.ROTATION_SECONDS);
    }

    /**
     * Buys the listing a click named, out of the buyer's own page. The id carries the window it was
     * shown in, and a click from an older window is refused: otherwise a player looking at a cheap
     * listing as the page turned over would pay the old price for whatever now sits in that slot.
     * The slot is resolved against the buyer's listings, never anyone else's, so a slot number
     * copied from another player's screen buys what is in that slot on this player's own.
     */
    public static ShopPurchaseResult purchase(ServerPlayer player, String wireId) {
        long[] parsed = ShopRotation.parseWireId(wireId);
        if (parsed == null) return ShopPurchaseResult.UNKNOWN_ENTRY;
        Instant now = Instant.now();
        if (parsed[0] != windowNow(now)) return ShopPurchaseResult.ROTATED;
        List<ShopRotation.Listing> listings = current(now, player.getUUID());
        int slot = (int) parsed[1];
        if (slot < 0 || slot >= listings.size()) return ShopPurchaseResult.UNKNOWN_ENTRY;
        return ShopPurchaseService.purchase(player, listings.get(slot).entry(), now);
    }

    /** What a player has left of one slot this rotation, for the page. */
    public static int remaining(ServerPlayer player, ShopRotation.Listing listing, Instant now) {
        return ShopPurchaseRules.remaining(listing.entry(),
                RaidPlayerRecords.get(player.getUUID()).purchasesOf(ShopRotation.entryId(listing.slot())), now);
    }
}
