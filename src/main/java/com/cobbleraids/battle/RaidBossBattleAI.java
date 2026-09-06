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
 */
public final class RaidBossBattleAI implements BattleAI {
    private final BattleAI delegate = new RandomBattleAI();

    @Override
    public ShowdownActionResponse choose(ActiveBattlePokemon pokemon, PokemonBattle battle, BattleSide side,
                                          ShowdownMoveset moveset, boolean canDynamax) {
        for (InBattleMove move : moveset.getMoves()) {
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
