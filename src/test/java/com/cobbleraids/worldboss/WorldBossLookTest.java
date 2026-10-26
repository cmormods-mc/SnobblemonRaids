package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WorldBossLookTest {

    @Test
    void theCopyStaysVisibleByDefaultButTheZoneHasNoGlow() {
        var look = WorldBossConfig.defaults().look();
        assertFalse(look.duelBossInvisible(), "hiding the copy is opt-in");
        assertTrue(look.noGlowInZone());
    }

    @Test
    void lookSurvivesAJsonRoundTripAndReadsFromTheVisualBlock() {
        var config = WorldBossConfig.fromJson(JsonParser.parseString(
                "{\"visual\":{\"duel_boss_invisible\":true,\"no_glow_in_zone\":false}}").getAsJsonObject());
        assertTrue(config.look().duelBossInvisible());
        assertFalse(config.look().noGlowInZone());
        assertEquals(config.look(), WorldBossConfig.fromJson(config.toJson()).look());
        assertEquals(config.wallColor(), WorldBossConfig.fromJson(config.toJson()).wallColor(), "the rest of visual is untouched");
    }
}
