package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WorldBossNightTest {

    @Test
    void vanillasNightIsDuskToDawn() {
        assertFalse(WorldBossRules.isNight(0, 13000, 23000), "sunrise");
        assertFalse(WorldBossRules.isNight(6000, 13000, 23000), "noon");
        assertFalse(WorldBossRules.isNight(12999, 13000, 23000));
        assertTrue(WorldBossRules.isNight(13000, 13000, 23000), "dusk starts the night");
        assertTrue(WorldBossRules.isNight(18000, 13000, 23000), "midnight");
        assertTrue(WorldBossRules.isNight(22999, 13000, 23000));
        assertFalse(WorldBossRules.isNight(23000, 13000, 23000), "dawn ends it");
    }

    @Test
    void theClockIsTakenModuloADayAndHandlesRangesThatWrapPastMidnight() {
        assertTrue(WorldBossRules.isNight(24000L * 12 + 18000, 13000, 23000), "day 12 at midnight");
        assertFalse(WorldBossRules.isNight(24000L * 12 + 6000, 13000, 23000));
        assertTrue(WorldBossRules.isNight(23000, 22000, 2000), "wrapped range before midnight");
        assertTrue(WorldBossRules.isNight(1000, 22000, 2000), "wrapped range after midnight");
        assertFalse(WorldBossRules.isNight(12000, 22000, 2000));
        assertTrue(WorldBossRules.isNight(-1000, 22000, 2000), "a negative time still maps into the day");
    }

    @Test
    void nightOnlyIsOnByDefaultAndReadsFromTheSpawnBlock() {
        var defaults = WorldBossConfig.defaults().night();
        assertTrue(defaults.only());
        assertEquals(13000, defaults.fromTick());
        assertEquals(23000, defaults.toTick());
        var config = WorldBossConfig.fromJson(JsonParser.parseString(
                "{\"spawn\":{\"night_only\":false,\"night_from_tick\":12542,\"night_to_tick\":23460}}").getAsJsonObject());
        assertFalse(config.night().only());
        assertEquals(12542, config.night().fromTick());
        assertEquals(config.night(), WorldBossConfig.fromJson(config.toJson()).night());
    }

    @Test
    void badNightTimesAreRejected() {
        for (String bad : new String[] {"{\"spawn\":{\"night_from_tick\":24000}}", "{\"spawn\":{\"night_to_tick\":-1}}",
                "{\"spawn\":{\"night_from_tick\":5000,\"night_to_tick\":5000}}"}) {
            assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(JsonParser.parseString(bad).getAsJsonObject()), bad);
        }
    }
}
