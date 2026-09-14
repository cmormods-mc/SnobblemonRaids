package com.cobbleraids.raid;

/**
 * Controls only terminal side effects and boss-retention semantics.
 *
 * <p>STANDARD preserves CobbleRaids' existing progression, catch, reward, and retry behavior.
 * EXTERNAL is for addon-owned encounters such as Battle Tower floors: CobbleRaids still owns the
 * shared battle and party-state carryover, but the addon owns rewards/progression and the boss is
 * always removed when the encounter terminates.
 */
public enum RaidCompletionPolicy {
    STANDARD,
    EXTERNAL
}
