package com.cobbleraids.fault;

import net.minecraft.server.MinecraftServer;

/**
 * Runs {@link RaidConsistencyAudit} on a slow schedule so a 24/7 server reports its own corruption
 * instead of waiting for a player to notice something odd.
 *
 * <p>Five minutes rather than every tick: these invariants describe state that changes at the pace
 * of raids, not of ticks, and the sweep walks live sessions, lobbies, tracked spawns and the
 * scoreboard. Checking more often would cost real work to find nothing, and an inconsistency that
 * matters does not heal itself in the meantime -- it sits there until somebody clears it.
 */
public final class RaidConsistencyAuditScheduler {
    private static final long INTERVAL_TICKS = 20L * 60L * 5L;

    private static long tickCounter;

    private RaidConsistencyAuditScheduler() {}

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % INTERVAL_TICKS) != 0L) return;
        RaidConsistencyAudit.log(RaidConsistencyAudit.run(server));
    }

    /** Cleared with the rest of the per-server state; an integrated client reuses this JVM. */
    public static void onServerStopped() { tickCounter = 0L; }
}
