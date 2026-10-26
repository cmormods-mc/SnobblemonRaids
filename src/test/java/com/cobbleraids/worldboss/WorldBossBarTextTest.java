package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldBossBarTextTest {

    @Test
    void numbersAreShortWithAtMostOneDecimal() {
        assertEquals("950", WorldBossBarText.shortNumber(950));
        assertEquals("1k", WorldBossBarText.shortNumber(1000));
        assertEquals("4.3k", WorldBossBarText.shortNumber(4317));
        assertEquals("5k", WorldBossBarText.shortNumber(5000));
        assertEquals("12k", WorldBossBarText.shortNumber(12000));
        assertEquals("120k", WorldBossBarText.shortNumber(120400));
        assertEquals("1.3M", WorldBossBarText.shortNumber(1_250_000));
        assertEquals("0", WorldBossBarText.shortNumber(0));
    }

    @Test
    void thePercentBadgeGoesFromGreenToRed() {
        assertEquals(WorldBossBarText.HEALTHY, WorldBossBarText.percentColor(100));
        assertEquals(WorldBossBarText.HEALTHY, WorldBossBarText.percentColor(60));
        assertEquals(WorldBossBarText.HURT, WorldBossBarText.percentColor(59));
        assertEquals(WorldBossBarText.HURT, WorldBossBarText.percentColor(30));
        assertEquals(WorldBossBarText.CRITICAL, WorldBossBarText.percentColor(29));
        assertEquals(WorldBossBarText.CRITICAL, WorldBossBarText.percentColor(0));
    }

    @Test
    void timeLeftReadsInHoursAndMinutes() {
        assertEquals("under 1m", WorldBossBarText.timeLeft(0));
        assertEquals("42m", WorldBossBarText.timeLeft(42));
        assertEquals("1h", WorldBossBarText.timeLeft(60));
        assertEquals("1h 57m", WorldBossBarText.timeLeft(117));
    }

    @Test
    void theNameGradientStartsAndEndsOnItsColours() {
        int[] colors = WorldBossBarText.gradient(7, WorldBossBarText.NAME_FROM, WorldBossBarText.NAME_TO);
        assertEquals(7, colors.length);
        assertEquals(WorldBossBarText.NAME_FROM, colors[0]);
        assertEquals(WorldBossBarText.NAME_TO, colors[6]);
        assertEquals(1, WorldBossBarText.gradient(1, 0x112233, 0x445566).length);
        assertEquals(0x112233, WorldBossBarText.gradient(1, 0x112233, 0x445566)[0]);
        assertEquals(0, WorldBossBarText.gradient(0, 0x112233, 0x445566).length);
    }

    @Test
    void theLineHasEveryPartInOrder() {
        assertEquals("Giratina  ·  4.3k/5k  ·  86%  ·  3 fighting  ·  1h 57m",
                WorldBossBarText.build("Giratina", 4317, 5000, 86, 3, 117).getString());
        assertEquals("Gengar  ·  5k/5k  ·  100%  ·  0 fighting",
                WorldBossBarText.build("Gengar", 5000, 5000, 100, 0, -1).getString(), "no time part when the boss stays until beaten");
    }
}
