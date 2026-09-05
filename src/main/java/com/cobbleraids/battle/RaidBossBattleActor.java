package com.cobbleraids.battle;

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import java.util.UUID;

/**
 * Entity-backed raid boss actor. Extending Cobblemon's native PokemonBattleActor preserves the
 * normal PokemonEntity <-> battleId lifecycle and post-battle cleanup semantics.
 */
public final class RaidBossBattleActor extends PokemonBattleActor {
    public RaidBossBattleActor(UUID uuid, BattlePokemon pokemon, BattleAI ai) {
        super(uuid, pokemon, Float.MAX_VALUE, ai);
    }
}
