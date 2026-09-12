package com.cobbleraids.battle;

import java.util.List;

/**
 * Which slot a raid move should actually be aimed at.
 *
 * <p>Cobblemon positions actors on a mirrored field and derives "the slot opposite me" from the
 * field width. A raid is one boss facing up to four separately actored players, which that model
 * cannot express, and the geometry replacement only applies where a raid is known to exist -- on
 * the server, since RaidRegistry is server state. A player's client therefore falls back to
 * Cobblemon's own maths and can nominate a slot that is not a legal opponent.
 *
 * <p>Seen live in a three-player raid, where the actors are p1a, p2b and p3c with the boss at p4a:
 * the first player's client aimed Leafage at p2b, a teammate, and every move came back "Invalid
 * action choice". Solo raids never showed it, because there the mirrored slot happens to be the
 * boss.
 *
 * <p>Free of Minecraft types so the decision can be tested without a battle, which is the only
 * part of this that can be tested at all.
 */
public final class RaidMoveTargeting {

    private RaidMoveTargeting() {}

    /**
     * The target a raid move should carry, or null to leave the submitted one alone.
     *
     * <p>A target that is already a legal opponent is never rewritten: the player picked something
     * they are allowed to hit, and in a raid with one boss that is the boss. Anything else is
     * repointed at the first opponent, because a raid offers no choice of foe -- which is what
     * makes overriding the client's pick cost the player nothing.
     *
     * @param submitted the target the client sent, which may be an ally or a slot that is not there
     * @param opponentPnxs legal opponents, in battle order
     */
    public static String correctedTarget(String submitted, List<String> opponentPnxs) {
        if (opponentPnxs == null || opponentPnxs.isEmpty()) return null;
        if (submitted == null) return null;
        if (opponentPnxs.contains(submitted)) return null;
        return opponentPnxs.get(0);
    }
}
