package com.cobbleraids.fault;

import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records whether this mod's world-touching code is running on the server thread.
 *
 * <p>The {@code -raiddamage} path does {@code Pokemon.setCurrentHealth(...)} and
 * {@code battle.sendSidedUpdate(...)}, which are Minecraft state and not safe to touch from an
 * arbitrary thread. Whether it runs on the server thread is Cobblemon's choice, not ours. In
 * Cobblemon 1.7.3 it does: {@code ShowdownInterpreter.interpretMessage} hands its work to
 * {@code DistributionUtilsKt.runOnServer}, which calls {@code MinecraftServer.execute} (read from
 * the bytecode on 2026-09-15; an earlier version of this comment said there was no marshalling,
 * which was wrong).
 *
 * <p>The check stays because that answer holds for one Cobblemon version and no mixin into the
 * interpreter. Asking the running server keeps answering after an update, and costs one reference
 * comparison on paths that already do far more work than that.
 *
 * <p>Nothing here changes behaviour. It observes and reports, so a violation shows up as a log line
 * and an audit finding rather than as a corrupted entity three weeks later.
 */
public final class RaidThreadGuard {

    /**
     * Captured at SERVER_STARTED. Compared by reference rather than calling
     * {@code MinecraftServer.isSameThread()} so the check needs no server argument and can sit on
     * paths that only have a battle.
     */
    private static volatile Thread serverThread;

    /** Contexts seen off-thread, kept so the consistency audit can report them long after the fact. */
    private static final Map<String, Integer> OFF_THREAD = new ConcurrentHashMap<>();

    /**
     * Every context that has been checked at all, and how often.
     *
     * <p>Without this, "no violations" is ambiguous: it means either the path always ran on the
     * server thread, or the path never ran. A multiplayer test that forms raids but never exchanges a
     * move would report the Showdown damage path as clean while never having executed it once, which
     * is worse than no check at all because it looks like evidence.
     */
    private static final Map<String, Integer> OBSERVED = new ConcurrentHashMap<>();

    private RaidThreadGuard() {}

    public static void onServerStarted(MinecraftServer server) {
        // SERVER_STARTED is raised on the server thread itself, so this is the thread we want.
        serverThread = Thread.currentThread();
        OFF_THREAD.clear();
        OBSERVED.clear();
    }

    public static void onServerStopped() {
        serverThread = null;
        OFF_THREAD.clear();
        OBSERVED.clear();
    }

    /** True once the server thread is known; false during startup and after shutdown. */
    public static boolean isKnown() { return serverThread != null; }

    public static boolean onServerThread() {
        Thread known = serverThread;
        return known == null || known == Thread.currentThread();
    }

    /**
     * Notes that {@code context} is expected to run on the server thread, and reports if it does not.
     *
     * <p>Reporting goes through {@link RaidFaultBarrier}'s throttle, so a path that is wrong on every
     * single Showdown message costs one log line a minute rather than thousands.
     *
     * @return true if we are on the server thread
     */
    public static boolean expectServerThread(String context) {
        OBSERVED.merge(context, 1, Integer::sum);
        if (onServerThread()) return true;
        OFF_THREAD.merge(context, 1, Integer::sum);
        RaidFaultBarrier.report("off-server-thread:" + context, new IllegalStateException(
                "CobbleRaids touched world state from " + Thread.currentThread().getName()
                        + " instead of the server thread. This is a report, not a crash -- but entity"
                        + " and packet writes from this path are not thread safe."));
        return false;
    }

    /** Contexts observed off the server thread, with how many times, for the consistency audit. */
    public static Map<String, Integer> offThreadObservations() { return Map.copyOf(OFF_THREAD); }

    /** Every context checked so far and how often, so "clean" can be told apart from "never ran". */
    public static Map<String, Integer> observations() { return Map.copyOf(OBSERVED); }

    /** How many times {@code context} has been checked; zero means that code has not executed. */
    public static int timesObserved(String context) { return OBSERVED.getOrDefault(context, 0); }

    /** Names of contexts seen off-thread. */
    public static Set<String> offThreadContexts() { return Set.copyOf(OFF_THREAD.keySet()); }
}
