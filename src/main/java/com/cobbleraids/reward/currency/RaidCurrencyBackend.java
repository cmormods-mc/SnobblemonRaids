package com.cobbleraids.reward.currency;

import java.math.BigInteger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Isolates the optional economy-mod dependency behind a small surface, the same way
 * {@link com.cobbleraids.reward.RewardGuiBackend} isolates SkiesGUIs. Every other class talks only
 * to this interface via {@link RaidCurrencyBackends#active()}; no direct reference to an economy
 * mod exists outside a backend implementation.
 */
public interface RaidCurrencyBackend {

    /**
     * Credits the player, returning whether the currency actually arrived.
     *
     * <p><strong>Never throws.</strong> This runs inside the claim transaction, after the items
     * have already been placed in the inventory; RaidRewardService restores the whole claim when
     * granting throws, so an exception here would hand back a claim the player had already been
     * partly paid for -- a duplication bug on the retry. This is the same rule
     * RaidRewardGrantEngine.give() follows for an unresolvable item: report false, grant nothing,
     * let the rest of the reward stand.
     */
    boolean grant(ServerPlayer player, BigInteger amount);

    /** Backend name surfaced via /cobbleraids debug status, e.g. "cobbledollars" or "none". */
    String name();
}
