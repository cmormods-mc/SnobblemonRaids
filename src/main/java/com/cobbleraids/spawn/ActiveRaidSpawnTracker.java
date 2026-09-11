package com.cobbleraids.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Owns which natural raid bosses exist, where, since when, and when each stops being this mod's
 * problem. Extracted from RaidSpawnScheduler, which had all of this tangled into one loop alongside
 * entity resolution, chat and spawning.
 *
 * <p>That tangle is not hypothetical. maintainTrackedBosses() walked the live map and called
 * discard() from inside the walk; discard() synchronously fires ENTITY_UNLOAD, whose handler removes
 * that same entry, and the next structural change threw ConcurrentModificationException straight
 * into the server tick and stopped the server (fixed in f261da0). Keeping the map, its iteration
 * order and its removal rules in one class -- with every world interaction behind {@link Maintenance}
 * -- is what makes that re-entrancy reproducible in a unit test instead of on a live server.
 *
 * <p>Server-thread only, like the scheduler that owns it. Nothing here is synchronised, because a
 * second thread touching it would be the bug rather than something to defend against.
 */
final class ActiveRaidSpawnTracker {

    /** What the world says about one resolved boss right now. */
    record Presence(boolean removed, boolean battling, boolean lobbyActive, boolean playerNearby) {}

    /**
     * Everything the maintenance pass needs from the world. The tracker decides, the caller acts:
     * this is the seam that keeps the decision logic testable without a MinecraftServer.
     *
     * <p>{@code B} is the caller's boss handle -- a PokemonEntity in production, a stub in tests --
     * so the tracker never names a Minecraft type. It is resolved once per entry per pass and handed
     * back to every callback, because a lookup per callback would be the same work two or three
     * times over on exactly the branches that already do the most.
     */
    interface Maintenance<B> {
        /** The live boss, or null when its chunk or dimension is not loaded. Must not mutate the tracker. */
        B resolve(UUID bossId, TrackedRaidSpawn spawn);

        /** State of a boss that resolved. Never called with null. */
        Presence presence(B boss);

        /** The total lifetime cap ran out. Cancel any lobby, tell nearby players, discard. Boss may be null. */
        void onLifetimeExpired(UUID bossId, TrackedRaidSpawn spawn, B boss);

        /** Nobody has been near for despawn_seconds. Discard. Boss may be null. */
        void onIdleDespawn(UUID bossId, TrackedRaidSpawn spawn, B boss);

        /** A player is standing here and the boss is close to its lifetime cap. Boss is never null. */
        void onExpiryWarning(UUID bossId, TrackedRaidSpawn spawn, B boss, long secondsLeft);
    }

    private final Map<UUID, TrackedRaidSpawn> active = new LinkedHashMap<>();

    TrackedRaidSpawn track(UUID bossId, ResourceLocation definitionId, ResourceLocation dimension,
                           BlockPos position, long nowTick, int despawnSeconds, int maxLifetimeSeconds) {
        TrackedRaidSpawn spawn = new TrackedRaidSpawn(
                definitionId, dimension, position, nowTick, despawnSeconds, maxLifetimeSeconds);
        active.put(bossId, spawn);
        return spawn;
    }

    boolean forget(UUID bossId) { return active.remove(bossId) != null; }

    boolean isTracked(UUID bossId) { return active.containsKey(bossId); }

    boolean isEmpty() { return active.isEmpty(); }

    int size() { return active.size(); }

    void clear() { active.clear(); }

    /** A stable copy for callers that discard entities while walking, e.g. shutdown. */
    List<Map.Entry<UUID, TrackedRaidSpawn>> snapshot() { return List.copyOf(active.entrySet()); }

    /** Seconds of lifetime remaining, or -1 for a boss this tracker does not know (admin-spawned). */
    long secondsUntilExpiry(UUID bossId, long nowTick) {
        TrackedRaidSpawn spawn = active.get(bossId);
        return spawn == null ? -1L : Math.max(0L, spawn.secondsLeft(nowTick));
    }

    int countInDimension(ResourceLocation dimension) {
        int count = 0;
        for (TrackedRaidSpawn spawn : active.values()) {
            if (spawn.dimension().equals(dimension)) count++;
        }
        return count;
    }

    /**
     * Used as a filter over every loaded definition (~130 of them) on each spawn attempt, so this
     * counts with a plain loop that stops at the cap rather than building a stream pipeline per
     * definition.
     */
    boolean belowDefinitionCap(ResourceLocation definitionId, int maxConcurrent) {
        int count = 0;
        for (TrackedRaidSpawn spawn : active.values()) {
            if (spawn.definitionId().equals(definitionId) && ++count >= maxConcurrent) return false;
        }
        return true;
    }

    /** Whether any tracked boss in the dimension sits within minimumDistance of pos. */
    boolean anyWithin(ResourceLocation dimension, BlockPos pos, double minimumDistance) {
        if (minimumDistance <= 0.0) return false;
        double maxDistanceSqr = minimumDistance * minimumDistance;
        for (TrackedRaidSpawn spawn : active.values()) {
            // Recorded spawn positions keep this check working for bosses whose chunk is unloaded,
            // which a live entity lookup cannot do.
            if (!spawn.dimension().equals(dimension)) continue;
            if (spawn.position().distSqr(pos) < maxDistanceSqr) return true;
        }
        return false;
    }

    /** Forgets every boss tracked in a dimension whose level has been closed. */
    boolean forgetDimension(ResourceLocation dimension) {
        return active.entrySet().removeIf(entry -> entry.getValue().dimension().equals(dimension));
    }

    /**
     * Drops only bosses the caller can prove are gone. An entry that does not resolve is kept,
     * because "not loaded" and "no longer exists" are indistinguishable from a lookup alone and
     * treating the first as the second is what leaked untracked bosses before Phase 32.
     */
    void releaseProvablyGone(BiPredicate<UUID, TrackedRaidSpawn> provablyGone) {
        active.entrySet().removeIf(entry -> provablyGone.test(entry.getKey(), entry.getValue()));
    }

    /**
     * One maintenance pass over every tracked boss.
     *
     * <p>Walks a snapshot rather than the live map, and re-checks membership before each entry,
     * because the callbacks discard entities -- and a discard synchronously re-enters this tracker
     * through the scheduler's ENTITY_UNLOAD handler to release that boss's slot. Both the copy and
     * the re-check are what keep that re-entrancy from corrupting the pass; the copy is cheap,
     * bounded by max_active_raids.
     */
    <B> void maintain(long nowTick, Maintenance<B> world) {
        for (Map.Entry<UUID, TrackedRaidSpawn> entry : snapshot()) {
            UUID bossId = entry.getKey();
            TrackedRaidSpawn spawn = entry.getValue();
            // Already released by the unload handler during an earlier discard in this same pass.
            if (!active.containsKey(bossId)) continue;

            B boss = world.resolve(bossId, spawn);
            Presence presence = boss == null ? null : world.presence(boss);

            // Fallback only. The scheduler's ENTITY_UNLOAD handler already released the slot for
            // anything destroyed in a loaded chunk, and it runs synchronously inside discard().
            // This still catches the one case it cannot see: an entity destroyed while its section
            // is inaccessible skips PersistentEntitySectionManager.stopTracking, so no unload event
            // fires and the entry stays resolvable while reporting removed. An ordinary unloaded
            // boss does not resolve at all and is handled below.
            if (presence != null && presence.removed()) {
                active.remove(bossId);
                continue;
            }

            // A raid that is actually being fought always finishes. Everything below is about bosses
            // nobody is fighting; finalization discards the entity itself when the battle ends.
            boolean battling = presence != null && presence.battling();
            if (!battling && spawn.secondsLeft(nowTick) <= 0L) {
                // Total lifetime cap, checked before the idle timer because it is the only rule a
                // player cannot reset. despawn_seconds measures unattended time and restarts every
                // second somebody stands in range, so on its own a camped boss lives forever,
                // holding a max_active_raids slot and blocking min_distance_between_raids for
                // everyone else. A recruiting lobby deliberately does NOT extend this, or re-opening
                // one would be an unlimited refresh; RaidLobbyManager refuses to open one near
                // expiry instead.
                world.onLifetimeExpired(bossId, spawn, boss);
                active.remove(bossId);
                continue;
            }

            if (presence != null) {
                // Recruitment and combat own the boss lifecycle while either is active.
                if (battling || presence.lobbyActive()) {
                    spawn.keepAlive(nowTick);
                    continue;
                }
                if (presence.playerNearby()) {
                    spawn.keepAlive(nowTick);
                    // Only somebody standing here can see the boss, so this is the only case where
                    // a fading warning has an audience worth sending it to.
                    if (spawn.claimExpiryWarning(nowTick) > 0) {
                        world.onExpiryWarning(bossId, spawn, boss, Math.max(1L, spawn.secondsLeft(nowTick)));
                    }
                    continue;
                }
            }
            // An unresolved boss keeps accruing idle time rather than being dropped: its chunk not
            // being loaded is itself proof that nobody is standing next to it.

            if (!spawn.idleLongEnoughToDespawn(nowTick)) continue;

            world.onIdleDespawn(bossId, spawn, boss);
            // An unresolved boss cannot be discarded from here. Dropping it from tracking hands it
            // to onNaturalBossLoaded, which removes any untracked natural boss the moment it loads.
            active.remove(bossId);
        }
    }
}
