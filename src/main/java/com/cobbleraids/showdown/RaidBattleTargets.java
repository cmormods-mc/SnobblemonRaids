package com.cobbleraids.showdown;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;

/** Shared boss-target resolution for the -raiddamage/-raidheal instructions. */
final class RaidBattleTargets {
    private RaidBattleTargets() {}

    /** The boss actor's one non-fainted active Pokemon, or null if none is out. */
    static ActiveBattlePokemon bossActive(BattleActor bossActor) {
        if (bossActor == null) return null;
        for (ActiveBattlePokemon pokemon : bossActor.getActivePokemon()) {
            if (pokemon != null && !pokemon.isGone()) return pokemon;
        }
        return null;
    }

    /** The PNX slot prefix (e.g. "p2a") from a Showdown argument like "p2a: Mewtwo". */
    static String pnx(String argument) {
        if (argument == null) return null;
        int colon = argument.indexOf(':');
        return colon <= 0 ? null : argument.substring(0, colon);
    }
}
