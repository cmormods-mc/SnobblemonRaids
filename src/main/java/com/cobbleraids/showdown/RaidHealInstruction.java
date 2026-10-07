package com.cobbleraids.showdown;

import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.dispatch.InstructionSet;

/** Applies shared raid healing and keeps boss-side and player-side HP displays synchronized. */
public final class RaidHealInstruction extends RaidPoolInstruction {

    public RaidHealInstruction(InstructionSet instructionSet, BattleActor actor, BattleMessage publicMessage, BattleMessage privateMessage) {
        super(instructionSet, actor, publicMessage, privateMessage);
    }

    @Override
    protected void applyToPool(RaidSession raid, PokemonBattle battle, float amount) {
        raid.heal(amount);
    }
}
