package com.cobbleraids.network;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * S2C: the actual result of the player's claim -- what the server rolled and granted, or failure.
 *
 * <p>{@code currencyGranted} is a long rather than the BigInteger the economy API uses: the payout
 * policy caps a tier amount well inside a long, and the client only ever displays this number.
 */
public record RewardResultPayload(
        UUID raidId,
        boolean success,
        List<RewardItemPayload> granted,
        long currencyGranted,
        int raidPointsGranted,
        boolean hasMoreQueued
) {}
