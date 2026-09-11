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
     * Claims {@code raidId}, runs {@code sideEffects}, then always runs {@code cleanup} and releases
     * the claim. Does nothing at all if the raid is already being finalized.
     *
     * @return true if this call owned the finalization, false if another already did
     */
    public boolean finalizeOnce(UUID raidId, Runnable sideEffects, Runnable cleanup) {
        if (!claimed.add(raidId)) return false;
        try {
            sideEffects.run();
        } finally {
            // Nested, so a failure in cleanup itself cannot leak the claim either. A leaked claim is
            // the worst outcome available here: it makes the raid permanently unfinalizable, and
            // unlike the boss entity or the registry entry there is nothing else that would ever
            // release it.
            try {
                cleanup.run();
            } finally {
                claimed.remove(raidId);
            }
        }
        return true;
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
