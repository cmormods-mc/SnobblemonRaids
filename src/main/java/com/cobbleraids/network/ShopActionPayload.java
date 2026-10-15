package com.cobbleraids.network;


/**
 * C2S: the player asked for a page, or to buy something.
 *
 * <p>One payload for both because they carry the same two fields and the server validates both the
 * same way. {@code entryId} is empty for a page turn.
 *
 * <p>Everything here is a request, not an instruction. The page index is clamped and the entry id
 * is looked up in the catalogue; neither is trusted to be in range or to exist.
 */
public record ShopActionPayload(int pageIndex, String entryId) {
    /** Longer than any id the catalogue will accept, so a hostile client cannot send a novel. */
    public static final int MAX_ID_LENGTH = 64;

    public static ShopActionPayload turnTo(int pageIndex) {
        return new ShopActionPayload(pageIndex, "");
    }

    public static ShopActionPayload buy(int pageIndex, String entryId) {
        return new ShopActionPayload(pageIndex, entryId);
    }

    public boolean isPurchase() {
        return !entryId.isEmpty();
    }
}
