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
 * {@code RaidBossTraits.readStats}, so anything reaching here has already been checked.
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
            default -> Stats.SPEED;
        };
    }
}
