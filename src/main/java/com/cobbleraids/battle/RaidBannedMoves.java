package com.cobbleraids.battle;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import java.util.Set;

/**
 * Moves whose primary effect faints a Pokemon through a mechanism other than the normal Showdown
 * damage pipeline (a direct .faint() call rather than a tracked -damage/-heal instruction). Raid
 * boss HP is a virtual pool driven exclusively by RaidDamageInstruction/RaidHealInstruction, so a
 * faint that bypasses that pipeline -- e.g. Perish Song fainting every active Pokemon at once,
 * including the boss -- satisfies neither of RaidLifecycleCoordinator's victory/defeat paths and
 * leaves the battle interface open. Every move below either carries selfdestruct: "always"/"ifHit"
 * or calls .faint() directly (Destiny Bond, Final Gambit, Perish Song), and this is mechanically
 * checked against Cobblemon's actual bundled data/moves.js by validate_raid_banned_moves.py, rather
 * than trusted from a one-time manual read -- see that script for why a drifted list here fails
 * loudly instead of a raid boss quietly hanging a battle turn.
 */
public final class RaidBannedMoves {
    private static final Set<String> BANNED = Set.of(
            "explosion",
            "selfdestruct",
            "mistyexplosion",
            "finalgambit",
            "memento",
            "healingwish",
            "lunardance",
            "destinybond",
            "perishsong"
    );

    private RaidBannedMoves() {}

    public static boolean isBanned(String showdownMoveId) {
        return showdownMoveId != null && BANNED.contains(showdownMoveId);
    }

    public static String displayName(String showdownMoveId) {
        MoveTemplate template = Moves.getByName(showdownMoveId);
        return template != null ? template.getDisplayName().getString() : showdownMoveId;
    }
}
