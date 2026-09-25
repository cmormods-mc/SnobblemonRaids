package com.cobbleraids.battle;

import com.cobbleraids.RaidLog;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.api.net.NetworkPacket;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMadeInvalidChoicePacket;
import java.util.UUID;

/**
 * Entity-backed raid boss actor. Extending Cobblemon's native PokemonBattleActor preserves the
 * normal PokemonEntity <-> battleId lifecycle and post-battle cleanup semantics.
 *
 * <p>Confirmed by decompiling Cobblemon 1.7.3's {@code AIBattleActor}: its {@code sendUpdate}
 * re-invokes the AI only on {@code BattleMakeChoicePacket}, the packet sent for a fresh request.
 * When Showdown retroactively rejects a choice the boss already locked in -- {@code ErrorInstruction}
 * takes this path for any {@code |error|} text it has no special case for, which includes "Can't
 * undo: A trapping/disabling effect would cause undo to leak information", exactly what Imprison,
 * Disable, Taunt or Encore produce when a faster actor's effect invalidates an already-submitted
 * choice -- Cobblemon instead sends {@code BattleMadeInvalidChoicePacket} and sets
 * {@code mustChoose = true}. That packet exists so a human client reopens its choice GUI; an AI
 * actor has no GUI and never listens for it, so the boss's choice is never resubmitted,
 * {@code allChoicesDone()} never becomes true, and the shared raid turn hangs forever -- with
 * nothing anywhere to grep for, since nothing throws. Re-invoking {@code onChoiceRequested()} here
 * is the same recovery step a human client performs by hand when its own choice is rejected.
 */
public final class RaidBossBattleActor extends PokemonBattleActor {
    public RaidBossBattleActor(UUID uuid, BattlePokemon pokemon, BattleAI ai) {
        super(uuid, pokemon, Float.MAX_VALUE, ai);
    }

    @Override
    public void sendUpdate(NetworkPacket<?> packet) {
        super.sendUpdate(packet);
        if (packet instanceof BattleMadeInvalidChoicePacket) {
            RaidLog.warn("Boss's move choice was rejected mid-turn (trapping/disabling undo); re-invoking its AI so the raid turn doesn't hang.");
            onChoiceRequested();
        }
    }
}
