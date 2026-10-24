package com.cobbleraids.network;

import java.util.List;

/**
 * S2C: one page of the shop, plus the balance to price it against.
 *
 * <p>Sent on open and again after every purchase, rather than the client adjusting its own copy.
 * The balance and the owned flags are the server's answer, and a screen that edited them locally
 * would show a player a purchase that had not happened.
 *
 * <p>{@code balance} is an {@code int} because {@link com.cobbleraids.reward.points.RaidPointsStore}
 * is: Raid Points are stored and clamped as an {@code int} at the source
 * ({@code RaidPlayerRecord.withPoints}), so there is nothing wider here to lose. This is a
 * different currency from {@link RewardResultPayload#currencyGranted}'s {@code long} -- that one is
 * an optional economy-mod grant amount, not a Raid Points balance -- so the two are not actually the
 * same value under two types.
 *
 * <p>{@code rotatesInMillis} is how long until this page's stock changes, or 0 for a page that does
 * not rotate. A duration rather than a wall-clock instant, so a client whose clock is wrong still
 * counts down the right amount of time.
 */
public record ShopPagePayload(
        String heading,
        int pageIndex,
        int pageCount,
        int balance,
        List<ShopEntryPayload> entries,
        long rotatesInMillis
) {}
