package com.cobbleraids.lifecycle;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a raid's terminal transition exactly once, and guarantees its cleanup runs even if the rest
 * of the transition fails.
 *
 * <p>Four paths can try to end the same raid -- the health pool reaching zero, Cobblemon reporting a
 * real faint, the combat timer expiring, and the last player leaving -- so finalization has always
 * been claimed once and refused thereafter. What it did not have was a guaranteed release.
 *
 * <p>That gap only became dangerous when the tick loop and the Cobblemon battle events were given
 * fault barriers. Before them, a throw halfway through finalization crashed the server: bad, but
 * loud, and a restart reset everything. Afterwards the barrier caught it and the raid was stranded
 * for good -- the claim was never released so every later attempt returned immediately, the session
 * stayed in RaidRegistry, the boss was never discarded, and its slot stayed held against
 * max_active_raids for the life of the server. Containment turned a crash into silent corruption.
 *
 * <p>So the cleanup half is separated from the side-effect half and runs in a finally. Rewards,
 * progression, catching and history are recoverable: losing them costs one raid's spoils and is
 * reported. Removing the session from the registry, discarding the boss and releasing the claim are
 * not recoverable and must happen however the rest went.
 *
 * <p>The throwable is deliberately allowed to propagate once cleanup has run, so the caller's
 * barrier still reports it. Swallowing it here would make a broken raid completely silent, which is
 * the failure mode this class exists to end.
 */
public final class RaidFinalizationGuard {

    private final Set<UUID> claimed = ConcurrentHashMap.newKeySet();

    /**
     * Claims {@code raidId}, runs {@code sideEffects}, then runs every one of {@code cleanupSteps}
     * and releases the claim. Does nothing at all if the raid is already being finalized.
     *
     * <p>Each cleanup step runs even when an earlier one throws. Cleanup is a list of separate
     * obligations -- end the battle, drop the registry entry, release the boss -- and when it was one
     * block, a throw from ending the battle skipped the rest and stranded the raid exactly as a
     * skipped cleanup would have. The first failure, side effects included, propagates once
     * everything has run; later ones are attached to it as suppressed so the report loses nothing.
     *
     * @return true if this call owned the finalization, false if another already did
     */
    public boolean finalizeOnce(UUID raidId, Runnable sideEffects, Runnable... cleanupSteps) {
        if (!claimed.add(raidId)) return false;
        Throwable failure = null;
        try {
            failure = attempt(sideEffects, null);
            for (Runnable step : cleanupSteps) failure = attempt(step, failure);
        } finally {
            // A leaked claim is the worst outcome available here: it makes the raid permanently
            // unfinalizable, and unlike the boss entity or the registry entry there is nothing else
            // that would ever release it.
            claimed.remove(raidId);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(failure);
        return true;
    }

    /** Runs {@code step}, returning the first failure seen so far with any new one suppressed onto it. */
    private static Throwable attempt(Runnable step, Throwable failure) {
        try {
            step.run();
        } catch (Throwable thrown) {
            if (failure == null) return thrown;
            if (thrown != failure) failure.addSuppressed(thrown);
        }
        return failure;
    }

    /** True while a finalization is in flight for this raid. */
    public boolean isFinalizing(UUID raidId) { return claimed.contains(raidId); }

    /**
     * Drops every claim. Bound to server shutdown: a server that stops with raids in flight would
     * otherwise leave their ids behind, and an integrated client reuses this JVM for every world it
     * opens.
     */
    public void clear() { claimed.clear(); }

    /** Number of claims currently held; only ever non-zero mid-finalization. */
    public int size() { return claimed.size(); }
}
