package com.cobbleraids.fault;

import com.cobbleraids.RaidLog;
import net.minecraft.server.MinecraftServer;

/**
 * Keeps a CobbleRaids failure inside CobbleRaids.
 *
 * <p>Fabric raises END_SERVER_TICK from inside {@code MinecraftServer.tickServer}, and Cobblemon
 * raises its battle events from the same thread, so an exception escaping either one is not a raid
 * that went wrong -- it is a crash report and a stopped dedicated server. This mod is built for a
 * box nobody is watching, where that trade is never worth making: one broken raid must not end
 * everyone else's session.
 *
 * <p>Only {@link Exception} is caught. An {@link Error} means the JVM or the mod's own linkage is
 * unsound -- OutOfMemoryError, or a NoSuchMethodError from Cobblemon drifting under our mixins --
 * and there is nothing left worth protecting at that point, so those still surface.
 */
public final class RaidFaultBarrier {
    private static final FaultLogThrottle THROTTLE = new FaultLogThrottle();

    /** A subsystem's per-tick entry point. Pass a static method reference so no lambda is allocated. */
    @FunctionalInterface
    public interface ServerTickTask {
        void tick(MinecraftServer server);
    }

    private RaidFaultBarrier() {}

    /**
     * Runs one subsystem's tick, containing any exception to that subsystem for that tick.
     *
     * <p>Deliberately not a per-raid circuit breaker: four of the five services return immediately
     * on an empty collection, and a per-raid quarantine map would put a lookup on that idle path
     * 20 times a second to guard a case that has never occurred. The subsystem is the unit here.
     */
    public static void safeTick(String subsystem, MinecraftServer server, ServerTickTask task) {
        try {
            task.tick(server);
        } catch (Exception ex) {
            report(subsystem, ex);
        }
    }

    /**
     * Logs a contained fault, rate limited per {@code context} so a fault that recurs every tick
     * costs one line a minute instead of 1200.
     */
    public static void report(String context, Throwable thrown) {
        long suppressed = THROTTLE.record(context, System.currentTimeMillis());
        if (suppressed == FaultLogThrottle.SUPPRESS) return;
        if (suppressed > 0L) {
            RaidLog.error("[{}] contained a failure; {} further failure(s) were suppressed since the last report."
                    + " Raids in this subsystem may be misbehaving; the server is still running.", context, suppressed, thrown);
        } else {
            RaidLog.error("[{}] contained a failure. Raids in this subsystem may be misbehaving;"
                    + " the server is still running.", context, thrown);
        }
    }

    /** Cleared with the rest of the per-server state so a new world starts reporting from scratch. */
    public static void onServerStopped() { THROTTLE.clear(); }
}
