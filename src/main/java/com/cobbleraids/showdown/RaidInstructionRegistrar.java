package com.cobbleraids.showdown;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownInterpreter;
import com.cobblemon.mod.common.battles.dispatch.InstructionSet;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import java.util.Iterator;
import kotlin.jvm.functions.Function4;
import kotlin.jvm.functions.Function6;

public final class RaidInstructionRegistrar {
    private static boolean registered;
    private RaidInstructionRegistrar() {}

    public static synchronized void register() {
        if (registered) return;

        // CobbleRaids currently emits -raiddamage/-raidheal as ordinary Showdown `update`
        // lines (Battle#add), so they must be registered on the update parser. Passing the
        // same BattleMessage as both public/private keeps the instruction implementation
        // compatible with the split form as well: target, exact amount, and source all live
        // on this one update line.
        ShowdownInterpreter.registerUpdateInstructionParser("-raiddamage",
            (Function4<PokemonBattle, InstructionSet, BattleMessage, Iterator<BattleMessage>, InterpreterInstruction>)
                (battle, set, message, iterator) -> new RaidDamageInstruction(null, message, message));
        ShowdownInterpreter.registerUpdateInstructionParser("-raidheal",
            (Function4<PokemonBattle, InstructionSet, BattleMessage, Iterator<BattleMessage>, InterpreterInstruction>)
                (battle, set, message, iterator) -> new RaidHealInstruction(null, message, message));

        // Retain split registration for compatibility with any future/private Showdown transport.
        ShowdownInterpreter.registerSplitInstructionParser("-raiddamage",
            (Function6<PokemonBattle, BattleActor, InstructionSet, BattleMessage, BattleMessage, Iterator<BattleMessage>, InterpreterInstruction>)
                (battle, actor, set, publicMessage, privateMessage, iterator) -> new RaidDamageInstruction(actor, publicMessage, privateMessage));
        ShowdownInterpreter.registerSplitInstructionParser("-raidheal",
            (Function6<PokemonBattle, BattleActor, InstructionSet, BattleMessage, BattleMessage, Iterator<BattleMessage>, InterpreterInstruction>)
                (battle, actor, set, publicMessage, privateMessage, iterator) -> new RaidHealInstruction(actor, publicMessage, privateMessage));
        registered = true;
    }
}
