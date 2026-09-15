package com.cobbleraids.raid;

import com.cobbleraids.config.CobbleRaidsConfig;
import java.util.Collection;

/**
 * How strong a boss becomes for the group that actually turned up.
 *
 * <p>Upward only: a definition's level is a floor, never a ceiling. A party that outclasses the
 * book gets a harder fight, and a party that does not gets exactly what the definition says. That
 * asymmetry is deliberate and it is what keeps the rewards honest -- loot is decided by rarity
 * tier, not by level, so a boss that could scale *down* would let a low-level group farm a
 * mythical's Mega Stone odds off a trivial fight. Nothing here can ever make a raid easier.
 *
 * <p>Applied once, when recruitment locks, beside {@link RaidScalingPolicy}: the same moment and
 * the same reasoning as max HP, which has scaled with the group since long before this existed.
 * Joining or leaving mid-fight rewrites neither.
 *
 * <p>Level also raises raid HP: {@link RaidScalingPolicy#forLevel} multiplies the pool by the boss
 * level over the definition level, because the party that raised the boss hits about that much
 * harder. A scaled boss hits harder and moves first, and the fight stays about as long.
 *
 * <p>Free of Minecraft types so the arithmetic is testable without a server.
 */
public final class RaidLevelPolicy {

    private RaidLevelPolicy() {}

    /**
     * The level a boss should fight at.
     *
     * @param definitionLevel the level its definition asks for, and the floor
     * @param partyAverage    mean level of the locked-in players' battle-ready Pokemon
     */
    public static int bossLevel(int definitionLevel, double partyAverage, CobbleRaidsConfig.DynamicLevel config) {
        if (config == null || !config.enabled()) return definitionLevel;
        if (!(partyAverage > 0.0)) return definitionLevel;

        int target = (int) Math.round(partyAverage) + config.levelOffset();
        int ceiling = Math.max(definitionLevel, config.maxLevel());
        return Math.min(ceiling, Math.max(definitionLevel, target));
    }

    /**
     * Mean of the levels supplied, or zero when there are none.
     *
     * <p>A flat mean across every battle-ready Pokemon of every participant, rather than per-player
     * bests: a group is as strong as what it can actually field, and averaging the best of each
     * party would read a six-Pokemon team and a one-Pokemon team as equals.
     */
    public static double average(Collection<Integer> levels) {
        if (levels == null || levels.isEmpty()) return 0.0;
        long total = 0;
        for (Integer level : levels) {
            if (level != null) total += Math.max(0, level);
        }
        return total / (double) levels.size();
    }
}
