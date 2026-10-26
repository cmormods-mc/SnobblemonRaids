package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The World Boss pool: clamping, crediting, growth and the one-time defeat. */
class WorldBossPoolTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void aStrikeLowersThePoolAndIsCredited() {
        WorldBossPool pool = new WorldBossPool(1000f);
        assertEquals(300f, pool.strike(alice, 300f));
        assertEquals(700f, pool.current());
        assertEquals(300f, pool.contributionOf(alice));
    }

    @Test
    void aStrikeIsClampedToWhatIsLeft() {
        WorldBossPool pool = new WorldBossPool(100f);
        pool.strike(alice, 80f);
        assertEquals(20f, pool.strike(bob, 500f), "overkill is not credited");
        assertEquals(0f, pool.current());
        assertEquals(20f, pool.contributionOf(bob));
    }

    @Test
    void contributionsAlwaysAddUpToTheDamageDealt() {
        WorldBossPool pool = new WorldBossPool(1000f);
        pool.strike(alice, 123f);
        pool.strike(bob, 456f);
        pool.strike(alice, 9999f);
        float sum = pool.contributions().values().stream().reduce(0f, Float::sum);
        assertEquals(pool.totalDamage(), sum, 0.001f);
        assertEquals(1000f, sum, 0.001f);
    }

    @Test
    void nonsenseStrikesChangeNothing() {
        WorldBossPool pool = new WorldBossPool(100f);
        assertEquals(0f, pool.strike(alice, 0f));
        assertEquals(0f, pool.strike(alice, -5f));
        assertEquals(0f, pool.strike(alice, Float.NaN));
        assertEquals(0f, pool.strike(alice, Float.POSITIVE_INFINITY));
        assertEquals(0f, pool.strike(null, 10f));
        assertEquals(100f, pool.current());
        assertTrue(pool.contributions().isEmpty());
    }

    @Test
    void defeatIsClaimedExactlyOnce() {
        WorldBossPool pool = new WorldBossPool(100f);
        assertFalse(pool.claimDefeat(), "not while health is left");
        pool.strike(alice, 100f);
        assertTrue(pool.claimDefeat());
        assertFalse(pool.claimDefeat());
        assertTrue(pool.isDefeated());
    }

    @Test
    void aDefeatedPoolTakesNoMoreStrikesOrGrowth() {
        WorldBossPool pool = new WorldBossPool(100f);
        pool.strike(alice, 100f);
        pool.claimDefeat();
        assertEquals(0f, pool.strike(bob, 50f));
        pool.growTo(500f);
        assertEquals(100f, pool.max());
        assertEquals(0f, pool.current());
    }

    @Test
    void growingKeepsTheDamageAlreadyDealt() {
        WorldBossPool pool = new WorldBossPool(1000f);
        pool.strike(alice, 400f);
        pool.growTo(1500f);
        assertEquals(1500f, pool.max());
        assertEquals(1100f, pool.current(), "the 500 added is all fresh health");
        assertEquals(400f, pool.totalDamage());
    }

    @Test
    void thePoolNeverShrinks() {
        WorldBossPool pool = new WorldBossPool(1000f);
        pool.strike(alice, 100f);
        pool.growTo(800f);
        pool.growTo(1000f);
        assertEquals(1000f, pool.max());
        assertEquals(900f, pool.current());
    }

    @Test
    void shareIsAFractionOfTheDamageDealt() {
        WorldBossPool pool = new WorldBossPool(1000f);
        assertEquals(0f, pool.shareOf(alice), "nothing dealt yet");
        pool.strike(alice, 100f);
        pool.strike(bob, 300f);
        assertEquals(0.25f, pool.shareOf(alice), 0.0001f);
        assertEquals(0.75f, pool.shareOf(bob), 0.0001f);
    }

    @Test
    void aRestoredPoolResumesWhereItWas() {
        WorldBossPool pool = new WorldBossPool(1000f, 250f, false, Map.of(alice, 750f));
        assertEquals(250f, pool.current());
        assertEquals(750f, pool.contributionOf(alice));
        assertEquals(250f, pool.strike(bob, 999f));
        assertTrue(pool.claimDefeat());
    }

    @Test
    void aRestoredDefeatedPoolStaysDefeatedAndCannotPayAgain() {
        WorldBossPool pool = new WorldBossPool(1000f, 0f, true, Map.of(alice, 1000f));
        assertTrue(pool.isDefeated());
        assertFalse(pool.claimDefeat(), "the reward was already claimed before the restart");
    }

    @Test
    void aBadRestoredCurrentIsBroughtInRange() {
        assertEquals(1000f, new WorldBossPool(1000f, 5000f, false, Map.of()).current());
        assertEquals(0f, new WorldBossPool(1000f, -5f, false, Map.of()).current());
    }

    @Test
    void aPoolNeedsPositiveHealth() {
        assertThrows(IllegalArgumentException.class, () -> new WorldBossPool(0f));
        assertThrows(IllegalArgumentException.class, () -> new WorldBossPool(-1f));
        assertThrows(IllegalArgumentException.class, () -> new WorldBossPool(Float.NaN));
    }

    @Test
    void manyDuelsStrikingAtOnceLoseNothingAndClaimTheDefeatOnce() throws Exception {
        int threads = 16;
        int strikesEach = 500;
        WorldBossPool pool = new WorldBossPool(threads * strikesEach * 1f);
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < threads; i++) players.add(UUID.randomUUID());
        for (UUID player : players) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < strikesEach; i++) {
                        pool.strike(player, 1f);
                        if (pool.claimDefeat()) claims.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(0f, pool.current());
        assertEquals(1, claims.get());
        assertEquals(threads * strikesEach, pool.totalDamage(), 0.5f);
    }
}
