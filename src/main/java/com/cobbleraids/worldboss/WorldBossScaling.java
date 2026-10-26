package com.cobbleraids.worldboss;

/**
 * How big the World Pool should be for the number of players currently fighting it.
 *
 * <p>Pure, so the curve can be tested and tuned without a server. The service calls it whenever a
 * duel starts and feeds the result to {@link WorldBossPool#growTo}, which only ever raises the pool.
 */
public final class WorldBossScaling {

    private WorldBossScaling() {}

    /**
     * The pool that makes the fight last about one minute per player involved, up to a ceiling.
     *
     * <p>Every player hits the pool at the same time, so n players deal n times as much per minute.
     * For the fight to last n minutes the pool has to be n times n minutes of one player's damage:
     * it grows with the <em>square</em> of the crowd. That is the owner's brief (2026-10-25). The
     * ceiling (also the owner's: 20 minutes) stops a big crowd being asked for a fight that grows
     * without end: past it the duration stays put and the pool grows only in step with the crowd.
     *
     * @param base                  smallest pool, from the definition
     * @param damagePerPlayerMinute what one player realistically deals to the pool in a minute,
     *                              including the breather between duels; measured with bots, then
     *                              set in the definition
     * @param activePlayers         distinct players who started a duel in the recent window
     * @param minutesPerPlayer      how long each extra player should add to the fight (1 by brief)
     * @param maxMinutes            the longest the fight is meant to last, whatever the crowd
     * @return the pool size, at least {@code base} and finite
     */
    public static float poolSize(float base, float damagePerPlayerMinute, int activePlayers,
                                 float minutesPerPlayer, float maxMinutes) {
        int players = Math.max(1, activePlayers);
        float minutes = Math.min(minutesPerPlayer * players, maxMinutes);
        float wanted = damagePerPlayerMinute * minutes * players;
        if (!Float.isFinite(wanted)) return base;
        return Math.max(base, wanted);
    }
}
