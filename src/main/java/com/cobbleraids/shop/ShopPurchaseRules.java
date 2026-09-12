package com.cobbleraids.shop;

/**
 * Whether a purchase is allowed, decided without touching a server.
 *
 * <p>Split out from {@link ShopPurchaseService} so the decision can be tested exhaustively while
 * the service is left holding only the parts that need a world: resolving an item, adding a Pokemon,
 * writing the record. The ordering below is the part worth pinning -- an entry the player already
 * owns should say so rather than complain about their balance, and a balance check must come before
 * anything is handed over.
 */
public final class ShopPurchaseRules {

    private ShopPurchaseRules() {}

    /**
     * The answer for everything that can be known before the world is involved.
     *
     * @param entry         the listing, or null when the id matched nothing
     * @param balance       the player's Raid Points
     * @param alreadyOwned  whether a once-per-player entry has been bought before
     * @return null when nothing blocks the purchase and the caller should go on to grant it
     */
    public static ShopPurchaseResult check(ShopEntry entry, int balance, boolean alreadyOwned) {
        if (entry == null) return ShopPurchaseResult.UNKNOWN_ENTRY;
        // Ownership before affordability: telling a player who already owns something that they
        // cannot afford it sends them off to earn points for a purchase that will never work.
        if (entry.oncePerPlayer() && alreadyOwned) return ShopPurchaseResult.ALREADY_OWNED;
        if (balance < entry.cost()) return ShopPurchaseResult.NOT_ENOUGH_POINTS;
        return null;
    }

    /** How many points short the player is, for a message worth reading. */
    public static int shortfall(ShopEntry entry, int balance) {
        if (entry == null) return 0;
        return Math.max(0, entry.cost() - balance);
    }
}
