package com.cobbleraids.api.encounter;

/**
 * Which of a raid's side effects an owned encounter has. Every one is off unless the owner turns
 * it on; nothing here follows CobbleRaids' own server config.
 *
 * @param catchable   roll the raid catch on victory. The catch copies the boss into a participant's
 *                    storage, so leave this off for any boss that must never be obtainable
 * @param raidRewards queue CobbleRaids' own raid rewards on victory
 * @param raidHistory count the victory in players' raid history (wins, species defeated)
 * @param progression award experience and EVs to the participants' Pokemon on victory
 * @param carryHealth copy battle damage and faints back onto the participants' real Pokemon at the
 *                    end, win or lose
 * @param carryPp     copy spent PP back onto the participants' real Pokemon at the end, win or lose
 */
public record EncounterPolicy(
        boolean catchable,
        boolean raidRewards,
        boolean raidHistory,
        boolean progression,
        boolean carryHealth,
        boolean carryPp) {

    private static final EncounterPolicy NONE = new EncounterPolicy(false, false, false, false, false, false);

    /** No side effects at all: the battle happens and nothing else does. */
    public static EncounterPolicy none() {
        return NONE;
    }

    public EncounterPolicy withProgression(boolean enabled) {
        return new EncounterPolicy(catchable, raidRewards, raidHistory, enabled, carryHealth, carryPp);
    }

    public EncounterPolicy withCarryover(boolean health, boolean pp) {
        return new EncounterPolicy(catchable, raidRewards, raidHistory, progression, health, pp);
    }

    public EncounterPolicy withRaidRewards(boolean enabled) {
        return new EncounterPolicy(catchable, enabled, raidHistory, progression, carryHealth, carryPp);
    }

    public EncounterPolicy withRaidHistory(boolean enabled) {
        return new EncounterPolicy(catchable, raidRewards, enabled, progression, carryHealth, carryPp);
    }

    public EncounterPolicy withCatchable(boolean enabled) {
        return new EncounterPolicy(enabled, raidRewards, raidHistory, progression, carryHealth, carryPp);
    }
}
