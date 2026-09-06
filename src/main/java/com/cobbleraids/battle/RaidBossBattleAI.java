package com.cobbleraids.battle;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.ai.RandomBattleAI;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;

/**
 * Wraps Cobblemon's RandomBattleAI so a raid boss's own AI never picks a RaidBannedMoves entry.
 * The player-side rejection in RaidBattleSelectActionsMixin only covers player choices; the boss
 * chooses its own moves through this BattleAI, entirely inside the Java-side AI step, never via a
 * network packet, so it needed its own guard. Disabling the move on its own per-turn request uses
 * the same field Cobblemon already sets for an out-of-PP move, so RandomBattleAI simply treats it
 * as unusable and picks from whatever remains (falling back to Struggle only if nothing is left).
 *
 * Every move's PP is also restored here, every turn. Confirmed via decompiling Cobblemon 1.7.3:
 * RandomBattleAI's Struggle fallback is a synthetic MoveActionResponse("struggle", ...) that never
 * requires an actual "struggle" entry in the moveset, but BattleActor.setActionResponses validates
 * every response through MoveActionResponse.isValid(), which looks up the response's moveName in
 * the real moveset and rejects it outright if not found -- struggle is never a real moveset entry,
 * so this throws IllegalActionChoiceException and freezes the battle turn whenever every real move
 * is simultaneously unusable. A short normal encounter essentially never exhausts a Pokemon's real
 * PP; a raid boss fighting for many turns against multiple players routinely can. Since the boss is
 * already treated as an infinite-resource opponent everywhere else (pooled HP, uncatchable,
 * invulnerable), restoring PP here keeps it out of this Cobblemon-side gap entirely rather than
 * trying to patch Cobblemon's own Struggle validation.
 */
public final class RaidBossBattleAI implements BattleAI {
    private final BattleAI delegate = new RandomBattleAI();

    @Override
    public ShowdownActionResponse choose(ActiveBattlePokemon pokemon, PokemonBattle battle, BattleSide side,
                                          ShowdownMoveset moveset, boolean canDynamax) {
        for (InBattleMove move : moveset.getMoves()) {
            move.setPp(move.getMaxpp());
            if (RaidBannedMoves.isBanned(move.getId())) {
                move.setDisabled(true);
            }
        }
        return delegate.choose(pokemon, battle, side, moveset, canDynamax);
    }

    @Override
    public void onHealthChange(BattleHealthChangePacket packet) {
        delegate.onHealthChange(packet);
    }
}
