package com.cobbleraids.raid;

import com.cobbleraids.config.RaidDefinition;

/**
 * Applies scaling once, after recruitment freezes. Joining/leaving during combat never rewrites max
 * HP.
 *
 * <p>Two factors. Participants, which has always been here: more players, more health. And the
 * level the boss ended up at, which matters because RaidLevelPolicy can raise a boss to meet a
 * strong party while the pool stayed a flat number from the definition -- so a level 100 group
 * meeting a level 25 starter boss would raise it to 100 and then delete it in eight turns, Mega
 * Stone and all. Tying the pool to the level keeps a fight the same length whoever shows up.
 *
 * <p>Upward only, matching the level itself: a boss at its definition level has exactly the pool
 * its definition asks for, and nothing here can make a raid shorter than it was designed to be.
 */
public final class RaidScalingPolicy {
    private RaidScalingPolicy() {}

    /** Health for a boss fighting at its definition level. */
    public static long maxHealth(RaidDefinition definition, int participants) {
        return maxHealth(definition, participants, definition.level());
    }

    /**
     * Health for a boss fighting at {@code bossLevel}.
     *
     * <p>Proportional: a boss raised from 25 to 100 gets four times the pool, because the party
     * that raised it hits about four times as hard.
     */
    public static long maxHealth(RaidDefinition definition, int participants, int bossLevel) {
        return forLevel(definition.scaledHealth(participants), definition.level(), bossLevel);
    }

    /**
     * The participant-scaled pool, adjusted for the level the boss ended up at.
     *
     * <p>Takes plain numbers rather than a definition so it can be tested without one: parsing a
     * definition reaches CobbleRaidsConfigManager for its defaults, whose static initializer wants
     * a Fabric config directory that no unit test has.
     */
    public static long forLevel(long participantScaledHealth, int definitionLevel, int bossLevel) {
        if (definitionLevel <= 0 || bossLevel <= definitionLevel) return participantScaledHealth;

        double scaled = participantScaledHealth * (bossLevel / (double) definitionLevel);
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(scaled));
    }
}
