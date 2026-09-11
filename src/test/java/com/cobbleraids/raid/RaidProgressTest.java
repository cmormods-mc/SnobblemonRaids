package com.cobbleraids.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.lifecycle.RaidOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The raid state machine: shared health, contribution crediting, and terminal transitions.
 *
 * <p>This is the core of the mod and had no tests at all, because it was welded to a PokemonBattle
 * and a boss entity and could only be reached by starting a server and hitting something. Splitting
 * RaidProgress out of RaidSession is what made it reachable.
 *
 * <p>The transitions are where the danger is. Four separate paths can try to end the same raid --
 * the pool reaching zero, Cobblemon reporting a real faint, the combat timer expiring, and the last
 * player leaving -- and the mod has already shipped bugs from two of them racing.
 */
class RaidProgressTest {

    private static final float MAX_HEALTH = 1000f;
    private static final int NO_TIME_LIMIT = 0;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private RaidProgress active() {
        RaidProgress progress = new RaidProgress(MAX_HEALTH, NO_TIME_LIMIT, List.of(alice, bob));
        progress.activate();
        return progress;
    }

    @Nested
    @DisplayName("health pool")
    class Health {

        @Test
        @DisplayName("starts full and refuses a maximum below 1")
        void startsFull() {
            assertEquals(MAX_HEALTH, active().currentHealth());
            assertEquals(1f, new RaidProgress(0f, NO_TIME_LIMIT, List.of()).maxHealth(),
                    "a zero-health boss would be dead on arrival");
            assertEquals(1f, new RaidProgress(-50f, NO_TIME_LIMIT, List.of()).maxHealth());
        }

        @Test
        @DisplayName("damage is clamped to the health left, so overkill cannot inflate a share")
        void overkillIsClamped() {
            RaidProgress progress = active();
            progress.damage(alice, 400f);

            assertEquals(600f, progress.damage(bob, 10_000f), "only the remaining 600 can be dealt");
            assertEquals(400f, progress.contributionSnapshot().get(alice));
            assertEquals(600f, progress.contributionSnapshot().get(bob),
                    "crediting the full 10000 would hand bob 96% of the rewards");
        }

        @Test
        @DisplayName("healing cannot exceed the maximum")
        void healingIsCapped() {
            RaidProgress progress = active();
            progress.damage(alice, 100f);

            assertEquals(100f, progress.heal(500f));
            assertEquals(MAX_HEALTH, progress.currentHealth());
            assertEquals(0f, progress.heal(50f), "already full");
        }

        @Test
        @DisplayName("non-positive damage and healing are ignored")
        void nonPositiveIsIgnored() {
            RaidProgress progress = active();

            assertEquals(0f, progress.damage(alice, 0f));
            assertEquals(0f, progress.damage(alice, -100f));
            assertEquals(0f, progress.heal(-100f));
            assertEquals(MAX_HEALTH, progress.currentHealth());
            assertTrue(progress.contributionSnapshot().isEmpty());
        }

        @Test
        @DisplayName("a pool emptied by rounding still completes the raid")
        void epsilonEmptiesThePool() {
            RaidProgress progress = active();
            progress.damage(alice, MAX_HEALTH - 0.00005f);

            assertEquals(RaidSession.Status.COMPLETED, progress.status(),
                    "a float residue below the epsilon must not leave a raid unwinnable");
            assertEquals(0f, progress.currentHealth());
        }
    }

    @Nested
    @DisplayName("contribution")
    class Contribution {

        @Test
        @DisplayName("accumulates across hits")
        void accumulates() {
            RaidProgress progress = active();
            progress.damage(alice, 100f);
            progress.damage(alice, 150f);
            progress.damage(bob, 50f);

            assertEquals(250f, progress.contributionSnapshot().get(alice));
            assertEquals(50f, progress.contributionSnapshot().get(bob));
        }

        @Test
        @DisplayName("a withdrawn player stops earning but keeps what they earned")
        void withdrawnKeepsEarnedShare() {
            RaidProgress progress = active();
            progress.damage(alice, 200f);
            progress.removeParticipant(alice);
            progress.damage(alice, 500f);

            assertEquals(200f, progress.contributionSnapshot().get(alice),
                    "damage after leaving must not be credited");
            assertEquals(300f, progress.currentHealth(), "but it still hurts the boss");
        }

        @Test
        @DisplayName("damage from nobody in particular hurts the boss and credits no one")
        void unattributedDamage() {
            RaidProgress progress = active();

            assertEquals(100f, progress.damage(null, 100f));
            assertTrue(progress.contributionSnapshot().isEmpty());
        }

        @Test
        @DisplayName("the snapshot is a copy that cannot write back")
        void snapshotIsDetached() {
            RaidProgress progress = active();
            progress.damage(alice, 100f);

            var snapshot = progress.contributionSnapshot();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put(bob, 9999f));

            progress.damage(bob, 50f);
            assertFalse(snapshot.containsKey(bob), "an earlier snapshot must not see later damage");
        }
    }

    @Nested
    @DisplayName("terminal transitions")
    class Terminal {

        @Test
        @DisplayName("emptying the pool is a victory")
        void poolEmptyIsVictory() {
            RaidProgress progress = active();
            progress.damage(alice, MAX_HEALTH);

            assertEquals(RaidSession.Status.COMPLETED, progress.status());
            assertEquals(RaidOutcome.VICTORY, progress.outcome());
        }

        @Test
        @DisplayName("a raid that already ended ignores further damage and healing")
        void endedRaidIsInert() {
            RaidProgress progress = active();
            progress.damage(alice, MAX_HEALTH);

            assertEquals(0f, progress.damage(bob, 100f));
            assertEquals(0f, progress.heal(100f));
            assertEquals(RaidOutcome.VICTORY, progress.outcome(), "the outcome must not be rewritten");
        }

        @Test
        @DisplayName("the first terminal path to arrive wins, whichever it is")
        void firstTerminalPathWins() {
            RaidProgress victory = active();
            victory.damage(alice, MAX_HEALTH);
            assertFalse(victory.fail(), "a won raid cannot then be failed");
            assertFalse(victory.completeViaRealFaint());
            assertEquals(RaidOutcome.VICTORY, victory.outcome());

            RaidProgress defeat = active();
            assertTrue(defeat.fail());
            assertFalse(defeat.completeViaRealFaint(), "a lost raid cannot then be won");
            assertEquals(0f, defeat.damage(alice, MAX_HEALTH));
            assertEquals(RaidOutcome.DEFEAT, defeat.outcome());
        }

        @Test
        @DisplayName("a real faint wins the raid even with health left in the pool")
        void realFaintWins() {
            RaidProgress progress = active();
            progress.damage(alice, 100f);

            assertTrue(progress.completeViaRealFaint());
            assertEquals(RaidOutcome.VICTORY, progress.outcome());
            assertEquals(0f, progress.currentHealth());
            assertEquals(100f, progress.contributionSnapshot().get(alice),
                    "contribution earned before the faint still counts");
        }

        @Test
        @DisplayName("the raid fails only once every active player has gone")
        void failsWhenEmptied() {
            RaidProgress progress = active();

            assertFalse(progress.failIfNoActiveParticipants());
            progress.removeParticipant(alice);
            assertFalse(progress.failIfNoActiveParticipants(), "bob is still fighting");
            progress.removeParticipant(bob);

            assertTrue(progress.failIfNoActiveParticipants());
            assertEquals(RaidOutcome.DEFEAT, progress.outcome());
        }

        @Test
        @DisplayName("abort works from any non-terminal state and only once")
        void abortIsOneWay() {
            RaidProgress waiting = new RaidProgress(MAX_HEALTH, NO_TIME_LIMIT, List.of(alice));
            assertTrue(waiting.abort(), "a raid can be aborted before it activates");
            assertFalse(waiting.abort());
            assertEquals(RaidOutcome.ABORTED, waiting.outcome());

            RaidProgress won = active();
            won.damage(alice, MAX_HEALTH);
            assertFalse(won.abort(), "a finished raid cannot be aborted out of its outcome");
            assertEquals(RaidOutcome.VICTORY, won.outcome());
        }

        @Test
        @DisplayName("nothing happens before activate()")
        void waitingIsInert() {
            RaidProgress progress = new RaidProgress(MAX_HEALTH, NO_TIME_LIMIT, List.of(alice));

            assertEquals(RaidSession.Status.WAITING, progress.status());
            assertNull(progress.outcome());
            assertEquals(0f, progress.damage(alice, 500f), "the pool is not live until the raid is");
            assertFalse(progress.fail());
            assertFalse(progress.tickCombatTimer());
        }

        @Test
        @DisplayName("activate only promotes from WAITING")
        void activateIsNotAReset() {
            RaidProgress progress = active();
            progress.damage(alice, MAX_HEALTH);
            progress.activate();

            assertEquals(RaidSession.Status.COMPLETED, progress.status(),
                    "a late activate must not resurrect a finished raid");
        }
    }

    @Nested
    @DisplayName("participants")
    class Participants {

        @Test
        @DisplayName("everyone who joined stays in the historical roster")
        void historyKeepsLeavers() {
            RaidProgress progress = active();
            progress.removeParticipant(alice);

            assertEquals(2, progress.participants().size(), "participants() is who was ever here");
            assertEquals(1, progress.activeParticipants().size());
            assertFalse(progress.isActiveParticipant(alice));
            assertTrue(progress.isActiveParticipant(bob));
        }

        @Test
        @DisplayName("removing twice, or removing a stranger, reports that nothing happened")
        void removeIsIdempotent() {
            RaidProgress progress = active();

            assertTrue(progress.removeParticipant(alice));
            assertFalse(progress.removeParticipant(alice));
            assertFalse(progress.removeParticipant(UUID.randomUUID()));
            assertFalse(progress.removeParticipant(null));
        }

        @Test
        @DisplayName("a null player is never an active participant")
        void nullIsNeverActive() {
            assertFalse(active().isActiveParticipant(null));
        }
    }

    @Test
    @DisplayName("the combat timer only advances an active raid")
    void combatTimerNeedsAnActiveRaid() {
        RaidProgress timed = new RaidProgress(MAX_HEALTH, 1, List.of(alice));
        assertTrue(timed.isTimed());
        assertFalse(timed.tickCombatTimer(), "not active yet");

        timed.activate();
        for (int i = 1; i < 20; i++) assertFalse(timed.tickCombatTimer());
        assertTrue(timed.tickCombatTimer(), "expires on the 20th tick of a 1 second limit");
    }

    @Test
    @DisplayName("concurrent damage neither loses nor invents health")
    void concurrentDamageIsExact() throws Exception {
        // Damage arrives from Showdown instruction handling, and a raid has up to four players
        // whose hits are interpreted in the same batch. The invariant is arithmetic: everything
        // credited must equal everything removed from the pool, with nothing lost to a lost update.
        int players = 4;
        int hitsEach = 250;
        float perHit = 1f;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) ids.add(UUID.randomUUID());

        RaidProgress progress = new RaidProgress(players * hitsEach * perHit, NO_TIME_LIMIT, ids);
        progress.activate();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(players);
        for (UUID id : ids) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < hitsEach; i++) progress.damage(id, perHit);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "damage threads did not finish");

        assertEquals(0f, progress.currentHealth(), "the pool must land exactly on empty");
        float credited = 0f;
        for (float share : progress.contributionSnapshot().values()) credited += share;
        assertEquals(players * hitsEach * perHit, credited, 0.001f,
                "every point of damage dealt must be credited to exactly one player");
        assertEquals(RaidOutcome.VICTORY, progress.outcome());
    }
}
