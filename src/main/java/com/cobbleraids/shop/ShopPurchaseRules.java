package com.cobbleraids.shop;

import com.cobbleraids.catching.RaidPurchaseTally;
import java.time.Instant;

/**
 * Whether a purchase is allowed, decided without touching a server.
 *
 * <p>Split out from {@link ShopPurchaseService} so the decision can be tested exhaustively while the
 * service is left holding only the parts that need a world. The ordering below is the part worth
 * pinning: an entry the player has run out of should say so rather than complain about their
 * balance, and a balance check must come before anything is handed over.
 *
 * <p>The clock is a parameter rather than a call to {@code Instant.now()}, because a daily limit
 * whose rollover cannot be tested is a daily limit nobody can trust.
 */
public final class ShopPurchaseRules {

    private ShopPurchaseRules() {}

    /**
     * The answer for everything that can be known before the world is involved.
     *
     * @return null when nothing blocks the purchase and the caller should go on to grant it
     */
    public static ShopPurchaseResult check(ShopEntry entry, int balance, RaidPurchaseTally tally,
                                           Instant now) {
        if (entry == null) return ShopPurchaseResult.UNKNOWN_ENTRY;
        // The limit before the price: telling a player who has hit their daily cap that they cannot
        // afford it sends them off to earn points for a purchase that will still be refused.
        if (remaining(entry, tally, now) <= 0) return ShopPurchaseResult.LIMIT_REACHED;
        if (balance < entry.cost()) return ShopPurchaseResult.NOT_ENOUGH_POINTS;
        return null;
    }

    /** How many more the player may buy, or {@link Integer#MAX_VALUE} when there is no limit. */
    public static int remaining(ShopEntry entry, RaidPurchaseTally tally, Instant now) {
        if (entry == null) return 0;
        return entry.remaining(tally, windowOf(entry, now));
    }

    /** Which counting window this entry's tally belongs to right now. */
    public static long windowOf(ShopEntry entry, Instant now) {
        return entry.reset().windowOf(now);
    }

    /** How many points short the player is, for a message worth reading. */
    public static int shortfall(ShopEntry entry, int balance) {
        if (entry == null) return 0;
        return Math.max(0, entry.cost() - balance);
    }

    /**
     * Why the limit stopped them, in words.
     *
     * <p>Built here rather than fixed on the enum because the useful sentence depends on the entry:
     * "you already own that" and "you have bought all five of those today" are the same refusal and
     * would be equally useless as each other's message.
     */
    public static String limitMessage(ShopEntry entry) {
        if (entry == null) return ShopPurchaseResult.LIMIT_REACHED.message();
        if (entry.limit() == 1) {
            return entry.reset() == ShopResetPeriod.NEVER
                    ? "You have already bought that."
                    : "You have already bought that today.";
        }
        return "You have bought all " + entry.limit() + " of those "
                + entry.reset().windowNoun() + ".";
    }
}
