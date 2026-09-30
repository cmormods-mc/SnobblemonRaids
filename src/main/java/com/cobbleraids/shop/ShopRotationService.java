package com.cobbleraids.shop;

import com.cobbleraids.catching.RaidPlayerRecords;
import java.time.Instant;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * The live rotating page: which listings are current, and buying one.
 *
 * <p>Holds one cached roll, keyed by the window it was made for and the config it was made under,
 * so opening the page costs a comparison rather than a shuffle of the species pool. The cache is an
 * optimisation only: rolling is a pure function, so a dropped cache reproduces the same listings.
 */
public final class ShopRotationService {

    private record Cached(long window, ShopRotationConfig config, List<ShopRotation.Listing> listings) {}

    private static volatile Cached cached;

    private ShopRotationService() {}

    public static void invalidate() {
        cached = null;
        ShopRotationPool.invalidate();
    }

    public static long windowNow(Instant now) {
        return ShopResetPeriod.ROTATION.windowOf(now);
    }

    /** The listings current at {@code now}; empty when the rotation is switched off. */
    public static List<ShopRotation.Listing> current(Instant now) {
        ShopRotationConfig config = ShopCatalogManager.get().rotation();
        if (!config.enabled()) return List.of();
        long window = windowNow(now);
        Cached snapshot = cached;
        if (snapshot != null && snapshot.window() == window && snapshot.config().equals(config)) {
            return snapshot.listings();
        }
        List<ShopRotation.Listing> listings =
                ShopRotation.roll(window, ShopRotationPool.get(config.excludedLabels()), config);
        cached = new Cached(window, config, listings);
        return listings;
    }

    /** When the current listings rotate out, for the page to say. */
    public static Instant nextRotation(Instant now) {
        return Instant.ofEpochSecond((windowNow(now) + 1) * ShopResetPeriod.ROTATION_SECONDS);
    }

    /**
     * Buys the listing a click named. The id carries the window it was shown in, and a click from
     * an older window is refused: otherwise a player looking at a cheap listing as the page turned
     * over would pay the old price for whatever now sits in that slot.
     */
    public static ShopPurchaseResult purchase(ServerPlayer player, String wireId) {
        long[] parsed = ShopRotation.parseWireId(wireId);
        if (parsed == null) return ShopPurchaseResult.UNKNOWN_ENTRY;
        Instant now = Instant.now();
        if (parsed[0] != windowNow(now)) return ShopPurchaseResult.ROTATED;
        List<ShopRotation.Listing> listings = current(now);
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
