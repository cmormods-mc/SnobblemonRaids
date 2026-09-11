package com.cobbleraids.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The natural-boss tracking rules, extracted from RaidSpawnScheduler so they can be exercised
 * without a MinecraftServer.
 *
 * <p>These are the rules that have cost the most live debugging in this project: a boss whose chunk
 * unloaded reported itself removed and was dropped from tracking (Phase 32), finished raids held
 * their slot for a full despawn_seconds (0.8.33), and a re-entrant discard during the maintenance
 * walk threw ConcurrentModificationException onto the server thread and stopped the server
 * (f261da0). Every one of those was found on a running server. They are all reproducible here.
 */
class ActiveRaidSpawnTrackerTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.fromNamespaceAndPath("cobbleraids", "garchomp");
    private static final ResourceLocation MEWTWO = ResourceLocation.fromNamespaceAndPath("cobbleraids", "mewtwo");
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private static final int DESPAWN_SECONDS = 60;
    private static final int LIFETIME_SECONDS = 600;

    /** Stands in for the PokemonEntity the scheduler resolves. */
    private static final class FakeBoss {
        boolean removed;
        boolean battling;
        boolean lobbyActive;
        boolean playerNearby;
        boolean discarded;
    }

    /**
     * Records what the tracker asked the world to do. Subclasses override {@code afterDiscard} to
     * model the re-entrancy that a real {@code discard()} causes.
     */
    private static class RecordingWorld implements ActiveRaidSpawnTracker.Maintenance<FakeBoss> {
        final java.util.Map<UUID, FakeBoss> world = new java.util.LinkedHashMap<>();
        final List<UUID> lifetimeExpired = new ArrayList<>();
        final List<UUID> idleDespawned = new ArrayList<>();
        final List<Long> warnings = new ArrayList<>();
        final List<UUID> resolvedFor = new ArrayList<>();
        int resolveCalls;

        @Override
        public FakeBoss resolve(UUID bossId, TrackedRaidSpawn spawn) {
            resolveCalls++;
            resolvedFor.add(bossId);
            return world.get(bossId);
        }

        @Override
        public ActiveRaidSpawnTracker.Presence presence(FakeBoss boss) {
            return new ActiveRaidSpawnTracker.Presence(
                    boss.removed, boss.battling, boss.lobbyActive, boss.playerNearby);
        }

        @Override
        public void onLifetimeExpired(UUID bossId, TrackedRaidSpawn spawn, FakeBoss boss) {
            lifetimeExpired.add(bossId);
            if (boss != null) { boss.discarded = true; afterDiscard(bossId); }
        }

        @Override
        public void onIdleDespawn(UUID bossId, TrackedRaidSpawn spawn, FakeBoss boss) {
            idleDespawned.add(bossId);
            if (boss != null) { boss.discarded = true; afterDiscard(bossId); }
        }

        @Override
        public void onExpiryWarning(UUID bossId, TrackedRaidSpawn spawn, FakeBoss boss, long secondsLeft) {
            warnings.add(secondsLeft);
        }

        void afterDiscard(UUID bossId) {}
    }

    private final ActiveRaidSpawnTracker tracker = new ActiveRaidSpawnTracker();
    private final RecordingWorld world = new RecordingWorld();

    private UUID spawn(ResourceLocation definitionId, ResourceLocation dimension, BlockPos pos, long atTick) {
        UUID bossId = UUID.randomUUID();
        tracker.track(bossId, definitionId, dimension, pos, atTick, DESPAWN_SECONDS, LIFETIME_SECONDS);
        world.world.put(bossId, new FakeBoss());
        return bossId;
    }

    private UUID spawn(long atTick) {
        return spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), atTick);
    }

    @Nested
    @DisplayName("re-entrant discard during the maintenance walk")
    class ReentrantDiscard {

        /**
         * The f261da0 bug, made cheap to reproduce. A real discard() synchronously fires
         * ENTITY_UNLOAD, whose handler calls forget() for that boss -- a structural change to the
         * very map the pass is walking. Against the old code (a live Iterator over the map) this
         * threw ConcurrentModificationException straight into the server tick.
         */
        @Test
        @DisplayName("a discard that removes its own entry mid-pass does not corrupt the walk")
        void discardRemovingOwnEntry() {
            RecordingWorld reentrant = new RecordingWorld() {
                @Override void afterDiscard(UUID bossId) { tracker.forget(bossId); }
            };
            List<UUID> bosses = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID bossId = UUID.randomUUID();
                tracker.track(bossId, GARCHOMP, OVERWORLD, new BlockPos(i * 1000, 64, 0), 0L,
                        DESPAWN_SECONDS, LIFETIME_SECONDS);
                reentrant.world.put(bossId, new FakeBoss());
                bosses.add(bossId);
            }

            // Every boss is now idle past despawn_seconds, so every one discards and re-enters.
            tracker.maintain(DESPAWN_SECONDS * 20L, reentrant);

            assertEquals(5, reentrant.idleDespawned.size(), "every tracked boss should have been visited");
            assertEquals(bosses, reentrant.idleDespawned, "the pass must visit every boss in order");
            assertEquals(0, tracker.size(), "every entry should be gone");
        }

        /**
         * The other half of the same fix: one discard can release a *different* entry, because a
         * chunk unload takes its neighbours with it. That entry must be skipped, not re-processed.
         */
        @Test
        @DisplayName("an entry released by an earlier discard in the same pass is skipped")
        void discardReleasingAnotherEntry() {
            List<UUID> bosses = new ArrayList<>();
            RecordingWorld cascading = new RecordingWorld() {
                @Override void afterDiscard(UUID bossId) {
                    tracker.forget(bossId);
                    // The first boss's discard also takes the last one with it.
                    if (bossId.equals(bosses.getFirst())) tracker.forget(bosses.getLast());
                }
            };
            for (int i = 0; i < 3; i++) {
                UUID bossId = UUID.randomUUID();
                tracker.track(bossId, GARCHOMP, OVERWORLD, new BlockPos(i * 1000, 64, 0), 0L,
                        DESPAWN_SECONDS, LIFETIME_SECONDS);
                cascading.world.put(bossId, new FakeBoss());
                bosses.add(bossId);
            }

            tracker.maintain(DESPAWN_SECONDS * 20L, cascading);

            assertEquals(List.of(bosses.get(0), bosses.get(1)), cascading.idleDespawned,
                    "the third boss was already released by the first discard and must not be despawned again");
            assertFalse(cascading.resolvedFor.contains(bosses.get(2)),
                    "a released entry must not even be resolved");
            assertEquals(0, tracker.size());
        }
    }

    @Nested
    @DisplayName("lifetime cap")
    class LifetimeCap {

        @Test
        @DisplayName("expires a boss nobody is fighting once its total lifetime runs out")
        void expiresAtLifetime() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).playerNearby = true;

            tracker.maintain(LIFETIME_SECONDS * 20L, world);

            assertEquals(List.of(bossId), world.lifetimeExpired);
            assertTrue(world.world.get(bossId).discarded);
            assertEquals(0, tracker.size());
        }

        /**
         * The cap is checked before the idle timer on purpose: despawn_seconds restarts every second
         * somebody stands in range, so a camped boss would otherwise live forever, holding a
         * max_active_raids slot and blocking min_distance_between_raids for everyone else.
         */
        @Test
        @DisplayName("a camped boss cannot hold its slot forever by resetting the idle timer")
        void campingCannotDefeatTheCap() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).playerNearby = true;

            for (long tick = 20L; tick < LIFETIME_SECONDS * 20L; tick += 20L) {
                tracker.maintain(tick, world);
            }
            assertEquals(1, tracker.size(), "the idle timer keeps resetting while a player stands there");

            tracker.maintain(LIFETIME_SECONDS * 20L, world);
            assertEquals(List.of(bossId), world.lifetimeExpired);
        }

        @Test
        @DisplayName("a boss in battle is never expired by the cap")
        void battleBeatsTheCap() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).battling = true;

            tracker.maintain(LIFETIME_SECONDS * 40L, world);

            assertTrue(world.lifetimeExpired.isEmpty(), "finalization discards a battling boss, not the tracker");
            assertTrue(tracker.isTracked(bossId));
        }

        @Test
        @DisplayName("a recruiting lobby does not extend the cap")
        void lobbyDoesNotExtendTheCap() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).lobbyActive = true;

            tracker.maintain(LIFETIME_SECONDS * 20L, world);

            assertEquals(List.of(bossId), world.lifetimeExpired,
                    "re-opening a lobby would otherwise be an unlimited lifetime refresh");
        }
    }

    @Nested
    @DisplayName("a boss whose chunk is not loaded")
    class Unresolved {

        @Test
        @DisplayName("keeps accruing idle time instead of being dropped or kept alive")
        void unresolvedAccruesIdleTime() {
            UUID bossId = spawn(0L);
            world.world.remove(bossId);

            tracker.maintain(DESPAWN_SECONDS * 20L - 20L, world);
            assertTrue(tracker.isTracked(bossId), "not idle long enough yet");

            tracker.maintain(DESPAWN_SECONDS * 20L, world);
            assertEquals(List.of(bossId), world.idleDespawned);
            assertEquals(0, tracker.size(), "dropped from tracking so onNaturalBossLoaded removes it on load");
        }

        /**
         * The Phase 32 invariant. "Not loaded" and "no longer exists" are indistinguishable from a
         * lookup alone, and treating the first as the second is what leaked persistent bosses.
         */
        @Test
        @DisplayName("is not released merely for failing to resolve")
        void unresolvedIsNotReleased() {
            UUID bossId = spawn(0L);
            world.world.remove(bossId);

            tracker.maintain(20L, world);

            assertTrue(tracker.isTracked(bossId));
            assertTrue(world.idleDespawned.isEmpty());
            assertTrue(world.lifetimeExpired.isEmpty());
        }

        @Test
        @DisplayName("still expires on the lifetime cap, with no entity to discard")
        void unresolvedStillExpires() {
            UUID bossId = spawn(0L);
            world.world.remove(bossId);

            tracker.maintain(LIFETIME_SECONDS * 20L, world);

            assertEquals(List.of(bossId), world.lifetimeExpired);
            assertEquals(0, tracker.size());
        }
    }

    @Test
    @DisplayName("a resolved boss reporting removed releases its slot at once")
    void resolvedAndRemovedIsReleased() {
        UUID bossId = spawn(0L);
        world.world.get(bossId).removed = true;

        tracker.maintain(20L, world);

        assertEquals(0, tracker.size());
        assertTrue(world.idleDespawned.isEmpty(), "it is already gone; nothing to despawn");
    }

    @Test
    @DisplayName("the boss is resolved exactly once per entry per pass")
    void resolvesOncePerPass() {
        UUID first = spawn(0L);
        UUID second = spawn(0L);
        world.world.get(first).playerNearby = true;

        tracker.maintain(DESPAWN_SECONDS * 20L, world);

        assertEquals(2, world.resolveCalls,
                "a lookup per callback would repeat the work on exactly the busiest branches");
    }

    @Nested
    @DisplayName("expiry warnings")
    class Warnings {

        @Test
        @DisplayName("one warning per threshold, not one per second")
        void oneWarningPerThreshold() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).playerNearby = true;

            // From 70s remaining down to 5s remaining, once a second, as the real pass runs.
            for (long second = LIFETIME_SECONDS - 70; second <= LIFETIME_SECONDS - 5; second++) {
                tracker.maintain(second * 20L, world);
            }

            assertEquals(2, world.warnings.size(),
                    "expected exactly the 60s and 10s warnings, got " + world.warnings);
        }

        @Test
        @DisplayName("nobody is warned about a boss they cannot see")
        void noWarningWithoutAnAudience() {
            UUID bossId = spawn(0L);
            world.world.get(bossId).playerNearby = false;

            for (long second = LIFETIME_SECONDS - 70; second <= LIFETIME_SECONDS - 5; second++) {
                tracker.maintain(second * 20L, world);
            }

            assertTrue(world.warnings.isEmpty());
        }
    }

    @Nested
    @DisplayName("caps and spacing")
    class CapsAndSpacing {

        @Test
        @DisplayName("max_concurrent counts only the same definition")
        void definitionCap() {
            spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), 0L);
            assertTrue(tracker.belowDefinitionCap(GARCHOMP, 2));

            spawn(GARCHOMP, OVERWORLD, new BlockPos(500, 64, 0), 0L);
            assertFalse(tracker.belowDefinitionCap(GARCHOMP, 2));
            assertTrue(tracker.belowDefinitionCap(MEWTWO, 2), "another species must be unaffected");
        }

        @Test
        @DisplayName("per-dimension counts are independent")
        void dimensionCounts() {
            spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), 0L);
            spawn(MEWTWO, NETHER, new BlockPos(0, 64, 0), 0L);

            assertEquals(1, tracker.countInDimension(OVERWORLD));
            assertEquals(1, tracker.countInDimension(NETHER));
        }

        @Test
        @DisplayName("min_distance uses the recorded position, so it holds for unloaded bosses too")
        void distanceUsesRecordedPosition() {
            UUID bossId = spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), 0L);
            world.world.remove(bossId);

            assertTrue(tracker.anyWithin(OVERWORLD, new BlockPos(100, 64, 0), 200.0));
            assertFalse(tracker.anyWithin(OVERWORLD, new BlockPos(100, 64, 0), 50.0));
            assertFalse(tracker.anyWithin(NETHER, new BlockPos(0, 64, 0), 200.0),
                    "same coordinates in another dimension are not close");
        }

        @Test
        @DisplayName("a minimum distance of zero disables the check")
        void zeroDistanceDisablesSpacing() {
            spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), 0L);
            assertFalse(tracker.anyWithin(OVERWORLD, new BlockPos(0, 64, 0), 0.0));
        }
    }

    @Nested
    @DisplayName("slot release outside the maintenance pass")
    class SlotRelease {

        @Test
        @DisplayName("forget releases a slot and is safe for an untracked boss")
        void forgetIsIdempotent() {
            UUID bossId = spawn(0L);

            assertTrue(tracker.forget(bossId));
            assertFalse(tracker.forget(bossId), "a second release must report that it did nothing");
            assertFalse(tracker.forget(UUID.randomUUID()), "an admin-spawned boss was never tracked");
            assertEquals(0, tracker.size());
        }

        @Test
        @DisplayName("a closed dimension forgets only its own bosses")
        void forgetDimension() {
            spawn(GARCHOMP, OVERWORLD, new BlockPos(0, 64, 0), 0L);
            UUID nether = spawn(MEWTWO, NETHER, new BlockPos(0, 64, 0), 0L);

            assertTrue(tracker.forgetDimension(OVERWORLD));
            assertEquals(1, tracker.size());
            assertTrue(tracker.isTracked(nether));
            assertFalse(tracker.forgetDimension(OVERWORLD), "nothing left to forget");
        }

        @Test
        @DisplayName("only provably gone bosses are released")
        void releaseProvablyGone() {
            UUID present = spawn(0L);
            UUID unloaded = spawn(0L);
            UUID destroyed = spawn(0L);
            world.world.remove(unloaded);
            world.world.get(destroyed).removed = true;

            tracker.releaseProvablyGone((bossId, spawn) -> {
                FakeBoss boss = world.world.get(bossId);
                return boss != null && boss.removed;
            });

            assertTrue(tracker.isTracked(present));
            assertTrue(tracker.isTracked(unloaded), "unloaded is not the same as gone");
            assertFalse(tracker.isTracked(destroyed));
        }

        @Test
        @DisplayName("expiry countdown is reported for tracked bosses and -1 for anything else")
        void secondsUntilExpiry() {
            UUID bossId = spawn(0L);

            assertEquals(LIFETIME_SECONDS, tracker.secondsUntilExpiry(bossId, 0L));
            assertEquals(LIFETIME_SECONDS - 30, tracker.secondsUntilExpiry(bossId, 30 * 20L));
            assertEquals(0L, tracker.secondsUntilExpiry(bossId, LIFETIME_SECONDS * 40L), "never negative");
            assertEquals(-1L, tracker.secondsUntilExpiry(UUID.randomUUID(), 0L), "admin-spawned bosses report -1");
        }
    }
}
