package com.cobbleraids.lifecycle;

import java.util.function.BooleanSupplier;

/** An incomplete end may require retirement, but must never be replayed for its side effects. */
final class BattleTerminationRecovery {
    private BattleTerminationRecovery() {}

    static void finish(BooleanSupplier ended, Runnable end, BooleanSupplier stillRegistered, Runnable retire) {
        RuntimeException failure = null;
        try {
            if (!ended.getAsBoolean()) end.run();
        } catch (RuntimeException ex) {
            failure = ex;
        }
        try {
            if (stillRegistered.getAsBoolean()) retire.run();
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex;
            else if (failure != ex) failure.addSuppressed(ex);
        }
        if (failure != null) throw failure;
    }
}
