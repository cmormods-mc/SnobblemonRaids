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
        // Field conditions are applied inside Battle#start, called from deep inside
        // BattleRegistry.startBattle -- which is still on the stack that eventually returns the
        // RaidSession RaidFactory then binds into RaidRegistry. So for THIS one instruction, unlike
        // -raiddamage/-raidheal (which only ever fire mid-combat, long after binding), the session
        // reliably does not exist yet: RaidRegistry.get(battle) is null every time, and the id was
        // being read from it only to name the raid in the log. The battle's own id names it just as
        // well and is available immediately, so this no longer depends on registry timing at all.
        RaidSession raid = RaidRegistry.get(battle);
        Object raidId = raid != null ? raid.getId() : battle.getBattleId();

        String kind = argument(0);
        String requested = argument(1);
        String applied = argument(2);

        if (applied.isEmpty() || !applied.equalsIgnoreCase(requested)) {
            RaidLog.warn("Raid {} asked Showdown for {} '{}' and it holds '{}' instead",
                    raidId, kind, requested, applied.isEmpty() ? "nothing" : applied);
            return;
        }
        RaidLog.info("Raid {} field: {} '{}' applied", raidId, kind, applied);
    }

    private String argument(int index) {
        String value = message.argumentAt(index);
        return value == null ? "" : value.trim();
    }
}
