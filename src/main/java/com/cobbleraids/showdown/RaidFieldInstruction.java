package com.cobbleraids.showdown;

import com.cobbleraids.RaidLog;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;

/**
 * Showdown reporting back which field condition it actually set.
 *
 * <p>Pure diagnostics, and worth the thirty lines for a reason: everything else about
 * {@link com.cobbleraids.api.encounter.EncounterRules}' weather and terrain happens inside the
 * Showdown process, where Java can see nothing. Without this, "the rain was applied" could only ever
 * be <i>inferred</i> from having asked for it -- and an id Showdown does not recognise, or a patch
 * that silently stopped running, would look exactly the same as success.
 *
 * <p>So the JS says what the field holds <b>after</b> it tried, read back from
 * {@code battle.field.weather} rather than from what it was told to set. A line saying
 * {@code requested=raindance applied=} is the failure it is there to make visible.
 *
 * <p>Changes nothing. It logs, and TDS #60 asks for exactly this kind of run-keyed diagnostic.
 */
final class RaidFieldInstruction implements InterpreterInstruction {

    private final BattleMessage message;

    RaidFieldInstruction(BattleMessage message) {
        this.message = message;
    }

    @Override
    public void invoke(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null) return;

        String kind = argument(0);
        String requested = argument(1);
        String applied = argument(2);

        if (applied.isEmpty() || !applied.equalsIgnoreCase(requested)) {
            RaidLog.warn("Raid {} asked Showdown for {} '{}' and it holds '{}' instead",
                    raid.getId(), kind, requested, applied.isEmpty() ? "nothing" : applied);
            return;
        }
        RaidLog.info("Raid {} field: {} '{}' applied", raid.getId(), kind, applied);
    }

    private String argument(int index) {
        String value = message.argumentAt(index);
        return value == null ? "" : value.trim();
    }
}
