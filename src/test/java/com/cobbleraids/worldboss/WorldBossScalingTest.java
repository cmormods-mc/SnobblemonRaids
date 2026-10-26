package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldBossScalingTest {

    private static final float BASE = 1000f;
    private static final float PER_MINUTE = 2000f;

    @Test
    void aLonePlayerGetsAboutOneMinute() {
        assertEquals(2000f, WorldBossScaling.poolSize(BASE, PER_MINUTE, 1, 1f, 20f));
    }

    @Test
    void zeroOrNegativePlayersCountAsOne() {
        assertEquals(2000f, WorldBossScaling.poolSize(BASE, PER_MINUTE, 0, 1f, 20f));
        assertEquals(2000f, WorldBossScaling.poolSize(BASE, PER_MINUTE, -3, 1f, 20f));
    }

    @Test
    void theFightLastsAboutOneMinutePerPlayer() {
        for (int players : new int[] {1, 2, 5, 10, 20}) {
            float pool = WorldBossScaling.poolSize(BASE, PER_MINUTE, players, 1f, 20f);
            float minutes = pool / (PER_MINUTE * players);
            assertEquals(players, minutes, 0.001f, players + " players");
        }
    }

    @Test
    void neverBelowTheBase() {
        assertEquals(BASE, WorldBossScaling.poolSize(BASE, 1f, 1, 1f, 20f));
    }

    @Test
    void anOverflowingValueFallsBackToTheBase() {
        assertEquals(BASE, WorldBossScaling.poolSize(BASE, Float.MAX_VALUE, 40, 1f, 20f));
    }

    @Test
    void theResultIsAlwaysFinite() {
        assertTrue(Float.isFinite(WorldBossScaling.poolSize(BASE, Float.NaN, 10, 1f, 20f)));
    }

    @Test
    void pastTheCeilingTheFightStopsGettingLonger() {
        for (int players : new int[] {21, 40, 100}) {
            float pool = WorldBossScaling.poolSize(BASE, PER_MINUTE, players, 1f, 20f);
            float minutes = pool / (PER_MINUTE * players);
            assertEquals(20f, minutes, 0.001f, players + " players");
        }
    }

    @Test
    void theCeilingIsTheDefinitionsToChoose() {
        float pool = WorldBossScaling.poolSize(BASE, PER_MINUTE, 10, 1f, 5f);
        assertEquals(5f, pool / (PER_MINUTE * 10), 0.001f);
    }
}
