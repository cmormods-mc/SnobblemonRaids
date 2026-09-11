package com.cobbleraids.reward.currency;

import java.math.BigInteger;
import net.minecraft.server.level.ServerPlayer;

/**
 * What runs when no supported economy mod is installed. Grants nothing and says so, so a payout
 * configured on a server without the mod is visibly not happening rather than silently swallowed.
 */
public final class NoCurrencyBackend implements RaidCurrencyBackend {

    @Override
    public boolean grant(ServerPlayer player, BigInteger amount) {
        return false;
    }

    @Override
    public String name() {
        return "none";
    }
}
