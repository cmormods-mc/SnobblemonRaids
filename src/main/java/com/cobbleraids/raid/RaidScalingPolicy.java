package com.cobbleraids.raid;

import com.cobbleraids.config.RaidDefinition;

/** Applies scaling once, after recruitment freezes. Joining/leaving during combat never rewrites max HP. */
public final class RaidScalingPolicy {
    private RaidScalingPolicy() {}
    public static long maxHealth(RaidDefinition definition, int participants) {
        return definition.scaledHealth(participants);
    }
}
