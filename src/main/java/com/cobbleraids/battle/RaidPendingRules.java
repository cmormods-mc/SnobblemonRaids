package com.cobbleraids.battle;

import com.cobbleraids.api.encounter.EncounterRules;

/**
 * The rules of the raid battle currently being started.
 *
 * <p>A handoff, and it exists because of an ordering that cannot be changed from here: Cobblemon
 * writes the {@code >start} payload from inside {@code BattleRegistry.startBattle}, and the
 * {@link com.cobbleraids.raid.RaidSession} that owns the rules is not built -- let alone registered
 * -- until that call has returned. So the mixin that rewrites the payload cannot look the rules up;
 * they have to be put somewhere it can reach first.
 *
 * <p>A plain static is safe here for one reason, and it is worth stating rather than assuming:
 * <b>every path through this is on the server thread</b>. {@code EncounterService.start} asserts it
 * (TDS #32's rule that Minecraft and Cobblemon mutation is main-thread), and a raid started any
 * other way arrives through the same factory on the same thread. If that ever stops being true this
 * is the first thing that breaks, which is why it is one class with three methods rather than a
 * field hidden on something larger.
 *
 * <p>Set immediately before the battle is built and cleared in a {@code finally}, so a battle that
 * throws on the way up cannot leave its rules lying in wait for the next one.
 */
public final class RaidPendingRules {

    private static EncounterRules pending = EncounterRules.none();

    private RaidPendingRules() {}

    /** Names the rules the next battle built on this thread is fought under. */
    public static void set(EncounterRules rules) {
        pending = rules == null ? EncounterRules.none() : rules;
    }

    /** What the battle being started right now is fought under; never null. */
    public static EncounterRules current() {
        return pending;
    }

    /** Must run once the battle is built, whether or not building it worked. */
    public static void clear() {
        pending = EncounterRules.none();
    }
}
