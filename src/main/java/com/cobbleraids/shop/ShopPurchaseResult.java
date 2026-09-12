package com.cobbleraids.shop;

/**
 * Why a purchase did or did not happen.
 *
 * <p>Every value except {@link #BOUGHT} and {@link #BOUGHT_TO_PC} means nothing was taken and
 * nothing was given. That is the invariant the whole purchase path is built around: there is no
 * outcome in which a player is charged and left empty-handed, so there is no refund path to get
 * wrong.
 */
public enum ShopPurchaseResult {

    BOUGHT(true, "Purchased."),
    BOUGHT_TO_PC(true, "Purchased. Your party was full, so it went to your PC."),
    UNKNOWN_ENTRY(false, "That is not for sale."),
    ALREADY_OWNED(false, "You have already bought that."),
    NOT_ENOUGH_POINTS(false, "You do not have enough Raid Points."),
    NO_ROOM(false, "Your party and PC are both full."),
    UNRESOLVED(false, "That listing is broken. Tell an administrator."),
    FAILED(false, "The purchase could not be completed.");

    private final boolean success;
    private final String message;

    ShopPurchaseResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean success() {
        return success;
    }

    public String message() {
        return message;
    }
}
