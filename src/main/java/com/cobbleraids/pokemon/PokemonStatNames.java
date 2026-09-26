package com.cobbleraids.pokemon;

import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;

/**
 * The one place a written stat name becomes a Cobblemon {@link Stat}.
 *
 * <p>Shared because two things now read stat blocks out of hand-written JSON -- raid boss traits
 * and shop Pokemon -- and a mapping that disagreed between them would put an operator's Special
 * Attack investment somewhere else entirely, with nothing in any log to say so.
 *
 * <p>The names themselves, their accepted spellings and their bounds are validated upstream by
 * {@code RaidBossTraits.readStats} (and, for a renown boon's stat, {@code RenownBoon}'s own
 * validated set), so anything reaching here has already been checked -- which is exactly why an
 * unrecognised name below throws instead of picking a default: a name that gets this far without
 * having gone through one of those checks is a bug in this class's own contract, not a value a
 * caller should ever legitimately produce, and silently redirecting it onto another stat is the
 * kind of failure this project has already been burned by having to hunt for after the fact.
 */
public final class PokemonStatNames {

    private PokemonStatNames() {}

    public static Stat statFor(String name) {
        return switch (name) {
            case "hp" -> Stats.HP;
            case "attack" -> Stats.ATTACK;
            case "defence" -> Stats.DEFENCE;
            case "special_attack" -> Stats.SPECIAL_ATTACK;
            case "special_defence" -> Stats.SPECIAL_DEFENCE;
            case "speed" -> Stats.SPEED;
            default -> throw new IllegalArgumentException(
                    "Not a recognised stat name: '" + name + "' -- should have been rejected upstream");
        };
    }
}
