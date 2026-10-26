package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.cobbleraids.worldboss.WorldBossMusic.Track;
import org.junit.jupiter.api.Test;

class WorldBossMusicTest {

    @Test
    void theDuelTrackWinsAndTheBreatherCountsAsTheDuel() {
        assertEquals(Track.BOSS, WorldBossMusic.wanted(true, true));
        assertEquals(Track.BOSS, WorldBossMusic.wanted(true, false), "a chained player who stepped out still hears the fight");
        assertEquals(Track.ZONE, WorldBossMusic.wanted(false, true));
        assertEquals(Track.NONE, WorldBossMusic.wanted(false, false));
    }

    @Test
    void aTrackStartsWhenNothingIsPlayingOrTheWantedOneChanges() {
        assertTrue(WorldBossMusic.due(Track.ZONE, Track.NONE, 0, 100, 148));
        assertTrue(WorldBossMusic.due(Track.BOSS, Track.ZONE, 90, 100, 180), "entering a duel swaps the track at once");
        assertFalse(WorldBossMusic.due(Track.NONE, Track.ZONE, 0, 100, 148), "nothing wanted is a stop, not a start");
    }

    @Test
    void aTrackRestartsOnlyWhenItsLengthHasPassed() {
        assertFalse(WorldBossMusic.due(Track.ZONE, Track.ZONE, 1000, 1000 + 148 * 20 - 1, 148));
        assertTrue(WorldBossMusic.due(Track.ZONE, Track.ZONE, 1000, 1000 + 148 * 20, 148));
    }

    @Test
    void musicDefaultsAreOnAndPointAtAscensionLibsSounds() {
        var music = WorldBossConfig.defaults().music();
        assertTrue(music.enabled());
        assertEquals("ascensionlib:world_event.boss", music.bossSound());
        assertEquals("ascensionlib:world_event.zone", music.zoneSound());
    }

    @Test
    void musicSurvivesAJsonRoundTripAndRejectsBadValues() {
        var config = WorldBossConfig.fromJson(JsonParser.parseString(
                "{\"music\":{\"enabled\":false,\"boss_seconds\":90,\"volume\":0.5}}").getAsJsonObject());
        assertFalse(config.music().enabled());
        assertEquals(90, config.music().bossSeconds());
        var again = WorldBossConfig.fromJson(config.toJson());
        assertEquals(config.music(), again.music());
        for (String bad : new String[] {"{\"music\":{\"volume\":2}}", "{\"music\":{\"zone_seconds\":1}}", "{\"music\":{\"boss_sound\":\"Not A Sound\"}}"}) {
            assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(JsonParser.parseString(bad).getAsJsonObject()), bad);
        }
    }
}
