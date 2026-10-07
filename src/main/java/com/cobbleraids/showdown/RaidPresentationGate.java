package com.cobbleraids.showdown;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.dispatch.DispatchResultKt;
import com.cobblemon.mod.common.battles.dispatch.InstructionSet;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import com.cobblemon.mod.common.battles.dispatch.UntilDispatch;
import com.cobblemon.mod.common.battles.interpreter.instructions.MoveInstruction;
import kotlin.Unit;

/**
 * Holds what players see until the move that caused it has finished animating.
 *
 * <p>{@code InstructionSet.execute} calls every instruction's {@code invoke} back to back the moment
 * a message batch is parsed, long before any animation has played. Cobblemon's own damage instruction
 * copes by queueing its visible work on {@code battle.dispatch} behind an {@code UntilDispatch} that
 * waits for the causing {@link MoveInstruction}'s future. The raid instructions did not, so the boss's
 * bar, its entity health and (on a killing blow) the victory all landed at parse time.
 *
 * <p>Only the presentation moves here. The raid's pool is still changed immediately, so the Java state
 * stays authoritative and ordered. Anything with no animating cause (weather, a status, a residual)
 * runs on the next free slot of the queue, which still keeps it behind every earlier animation.
 */
final class RaidPresentationGate {
    /** A move's future never completing must not freeze the battle's whole dispatch queue. */
    private static final long MAX_WAIT_NANOS = 20_000_000_000L;

    private RaidPresentationGate() {}

    static void afterCause(PokemonBattle battle, InstructionSet set, InterpreterInstruction self, Runnable body) {
        MoveInstruction move = set != null && set.getMostRecentCauser(self) instanceof MoveInstruction m ? m : null;
        // An exception escaping a dispatch lambda makes Cobblemon end the battle, so contain it here.
        Runnable guarded = () -> RaidFaultBarrier.guard("raid-presentation", body);

        if (move == null) {
            battle.dispatchGo(() -> {
                guarded.run();
                return Unit.INSTANCE;
            });
            return;
        }

        battle.dispatch(() -> {
            long deadline = System.nanoTime() + MAX_WAIT_NANOS;
            boolean[] ran = {false};
            return new UntilDispatch(() -> {
                if (ran[0]) return true;
                boolean animationDone = move.getFuture().isDone();
                if (!animationDone && System.nanoTime() - deadline < 0) return false;
                ran[0] = true;
                guarded.run();
                return true;
            });
        });
    }
}
