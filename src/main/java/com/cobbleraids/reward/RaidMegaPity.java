package com.cobbleraids.reward;

import com.cobbleraids.config.CobbleRaidsConfig;

/**
 * Bad-luck protection on Mega Stones.
 *
 * <p>A 10% drop is a geometric distribution, and a geometric distribution has a long tail: at the
 * shipped numbers the average player finds a stone in four hours, but roughly one in twenty waits
 * three times that. "Rare but not too rare" is usually a statement about that tail rather than
 * about the mean, and the counter is what bounds it -- guaranteed by the twelfth mega-capable
 * raid, which is about six and three quarter hours.
 *
 * <p>The guarantee is also what lets the base rate be low. Ten percent with a floor under it
 * averages the same four hours as fifteen percent without one, and the distribution is far
 * tighter, which is strictly the better trade.
 *
 * <p>Counted in mega-capable raids, never in raids. Thirty percent of starter spawns and nearly
 * every legendary carry no stone at all, so counting those would make the guarantee mean much less
 * than the number suggests.
 *
 * <p>Free of Minecraft types so both decisions can be tested without a battle.
 */
public final class RaidMegaPity {

    /** Tera Shards share the Mega Stone namespace but are not stones. */
    private static final String TERA_SUFFIX = "_tera_shard";
    private static final String STONE_NAMESPACE = "mega_showdown";

    private RaidMegaPity() {}

    /**
     * Whether this claim's specialty selection should be replaced by a guaranteed stone.
     *
     * @param raidsSinceStone mega-capable raids this player has claimed since their last stone
     */
    public static boolean guaranteed(int raidsSinceStone, CobbleRaidsConfig.MegaPity config) {
        if (config == null || !config.enabled()) return false;
        return raidsSinceStone >= config.threshold();
    }

    /**
     * Whether a granted item is a Mega Stone, which is what resets the counter.
     *
     * <p>Identified by namespace rather than by a list of the twenty-three ids. A list would have
     * to be regenerated whenever mega_showdown adds a stone, and would silently stop resetting the
     * counter for the one it missed. The only other thing this mod grants from that namespace is a
     * Tera Shard, and validate_economy_probabilities asserts that stays true.
     */
    public static boolean isMegaStone(String itemId) {
        if (itemId == null) return false;
        int colon = itemId.indexOf(':');
        if (colon < 0) return false;
        if (!STONE_NAMESPACE.equals(itemId.substring(0, colon))) return false;
        return !itemId.endsWith(TERA_SUFFIX);
    }
}
