package com.cobbleraids.worldboss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldBossConfigAndRulesTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static WorldBossConfig withWindow(String start, String end) {
        return WorldBossConfig.fromJson(json("{\"window\":{\"start\":" + start + ",\"end\":" + end + "}}"));
    }

    // ---- config ----

    @Test
    void anEmptyFileIsTheDefaultsAndIsDisabled() {
        WorldBossConfig config = WorldBossConfig.fromJson(json("{}"));
        assertEquals(WorldBossConfig.defaults(), config);
        assertFalse(config.enabled(), "the system ships off");
    }

    @Test
    void aFreshInstallIsOnAndOpensAtMidnightEasternOnHalloween() {
        WorldBossConfig config = WorldBossConfig.halloween2026();
        assertTrue(config.enabled());
        assertEquals(config, WorldBossConfig.fromJson(config.toJson()));
        assertEquals(WorldBossRules.Window.BEFORE, WorldBossRules.window(config, Instant.parse("2026-10-31T03:59:59Z")));
        assertEquals(WorldBossRules.Window.OPEN, WorldBossRules.window(config, Instant.parse("2026-10-31T04:00:00Z")));
        assertEquals(WorldBossRules.Window.OPEN, WorldBossRules.window(config, Instant.parse("2026-11-03T04:59:59Z")));
        assertEquals(WorldBossRules.Window.AFTER, WorldBossRules.window(config, Instant.parse("2026-11-03T05:00:00Z")));
    }

    @Test
    void defaultsMatchTheOwnersBreathers() {
        WorldBossConfig config = WorldBossConfig.defaults();
        assertTrue(config.chain());
        assertEquals(30, config.breatherSeconds());
        assertEquals(45, config.allFaintedBreatherSeconds());
        assertEquals(128.0, config.radius());
        assertEquals(20f, config.maxMinutes());
        assertEquals(1f, config.minutesPerPlayer());
    }

    @Test
    void writingTheDefaultsAndReadingThemBackChangesNothing() {
        WorldBossConfig defaults = WorldBossConfig.defaults();
        assertEquals(defaults, WorldBossConfig.fromJson(defaults.toJson()));
    }

    @Test
    void aWindowRoundTrips() {
        WorldBossConfig config = withWindow("\"2026-10-31T00:00:00Z\"", "\"2026-11-03T00:00:00Z\"");
        assertEquals(config, WorldBossConfig.fromJson(config.toJson()));
        assertEquals(Optional.of(Instant.parse("2026-10-31T00:00:00Z")), config.windowStart());
    }

    @Test
    void keysAreReadFromTheirGroups() {
        WorldBossConfig config = WorldBossConfig.fromJson(json(
                "{\"enabled\":true,\"raid_definitions\":[\"cobbleraids:giratina\",\"cobbleraids:gengar\"],\"radius\":64,"
                        + "\"duel\":{\"breather_seconds\":10,\"all_fainted_breather_seconds\":20,\"chain\":false},"
                        + "\"reward\":{\"min_percent_of_average\":2.5,\"raid_points\":500}}"));
        assertTrue(config.enabled());
        assertEquals(java.util.List.of("cobbleraids:giratina", "cobbleraids:gengar"), config.raidDefinitions());
        assertEquals(64.0, config.radius());
        assertEquals(10, config.breatherSeconds());
        assertEquals(20, config.allFaintedBreatherSeconds());
        assertFalse(config.chain());
        assertEquals(0.025f, config.minOfAverage(), 0.00001f);
        assertEquals(500, config.rewardRaidPoints());
    }

    @Test
    void outOfRangeValuesNameTheKey() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"pool\":{\"max_minutes\":0}}"))).getMessage();
        assertTrue(message.contains("pool.max_minutes"), message);
        assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"radius\":2}")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"reward\":{\"min_percent_of_average\":101}}")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"duel\":{\"boss_level\":0}}")));
    }

    @Test
    void theFaintedBreatherCannotBeShorterThanTheNormalOne() {
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json(
                "{\"duel\":{\"breather_seconds\":60,\"all_fainted_breather_seconds\":30}}")));
    }

    @Test
    void wrongTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"pool\":5}")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"pool\":{\"base\":\"lots\"}}")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldBossConfig.fromJson(json("{\"window\":{\"start\":\"soon\"}}")));
    }

    @Test
    void aWindowMustEndAfterItStarts() {
        assertThrows(IllegalArgumentException.class,
                () -> withWindow("\"2026-11-03T00:00:00Z\"", "\"2026-10-31T00:00:00Z\""));
    }

    // ---- window ----

    @Test
    void noWindowMeansManualControl() {
        assertEquals(WorldBossRules.Window.MANUAL, WorldBossRules.window(WorldBossConfig.defaults(), Instant.now()));
    }

    @Test
    void theWindowOpensAtTheStartAndClosesAtTheEnd() {
        WorldBossConfig config = withWindow("\"2026-10-31T00:00:00Z\"", "\"2026-11-03T00:00:00Z\"");
        assertEquals(WorldBossRules.Window.BEFORE, WorldBossRules.window(config, Instant.parse("2026-10-30T23:59:59Z")));
        assertEquals(WorldBossRules.Window.OPEN, WorldBossRules.window(config, Instant.parse("2026-10-31T00:00:00Z")));
        assertEquals(WorldBossRules.Window.OPEN, WorldBossRules.window(config, Instant.parse("2026-11-02T23:59:59Z")));
        assertEquals(WorldBossRules.Window.AFTER, WorldBossRules.window(config, Instant.parse("2026-11-03T00:00:00Z")));
    }

    @Test
    void aWindowWithOnlyAnEndIsOpenUntilThen() {
        WorldBossConfig config = WorldBossConfig.fromJson(json("{\"window\":{\"end\":\"2026-11-03T00:00:00Z\"}}"));
        assertEquals(WorldBossRules.Window.OPEN, WorldBossRules.window(config, Instant.parse("2020-01-01T00:00:00Z")));
        assertEquals(WorldBossRules.Window.AFTER, WorldBossRules.window(config, Instant.parse("2027-01-01T00:00:00Z")));
    }

    // ---- breather, active players, reward ----

    @Test
    void aFaintedPartyWaitsFortyFiveSeconds() {
        WorldBossConfig config = WorldBossConfig.defaults();
        assertEquals(30, WorldBossRules.breatherSeconds(config, false));
        assertEquals(45, WorldBossRules.breatherSeconds(config, true));
    }

    @Test
    void onlyRecentDuellistsAreActive() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, Long> last = Map.of(a, 1_000_000L, b, 900_000L, c, 10L);
        assertEquals(2, WorldBossRules.activePlayers(last, 1_000_000L, 200_000L));
        assertEquals(0, WorldBossRules.activePlayers(Map.of(), 1_000_000L, 200_000L));
    }

    @Test
    void theRewardNeedsDamageAndAFractionOfTheAverage() {
        assertTrue(WorldBossRules.qualifies(50f, 100f, 0.05f), "the bar is 5");
        assertFalse(WorldBossRules.qualifies(4f, 100f, 0.05f));
        assertFalse(WorldBossRules.qualifies(0f, 0f, 0f), "a zero threshold still needs some damage");
        assertTrue(WorldBossRules.qualifies(1f, 1000f, 0f));
    }

    @Test
    void anEvenCrowdOfAnySizeIsPaid() {
        Map<UUID, Float> crowd = new java.util.HashMap<>();
        for (int i = 0; i < 500; i++) crowd.put(UUID.randomUUID(), 100f);
        float average = WorldBossRules.averageDamage(crowd);
        assertEquals(100f, average, 0.001f);
        assertTrue(crowd.values().stream().allMatch(dealt -> WorldBossRules.qualifies(dealt, average, 0.05f)));
    }

    @Test
    void theAverageCountsOnlyPlayersWhoDealtDamage() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertEquals(150f, WorldBossRules.averageDamage(Map.of(a, 100f, b, 200f, c, 0f)), 0.001f);
        assertEquals(0f, WorldBossRules.averageDamage(Map.of()));
        assertEquals(0f, WorldBossRules.averageDamage(Map.of(a, 0f)));
    }

    // ---- chain step ----

    @Test
    void theChainStartsOnlyAfterTheBreatherWhenEverythingIsFine() {
        assertEquals(WorldBossRules.Step.WAIT,
                WorldBossRules.nextStep(false, false, true, true, false, true, false));
        assertEquals(WorldBossRules.Step.START,
                WorldBossRules.nextStep(false, false, true, true, false, true, true));
    }

    @Test
    void theEndOfTheEventBeatsEverything() {
        assertEquals(WorldBossRules.Step.FINISHED,
                WorldBossRules.nextStep(true, true, false, false, true, false, false));
    }

    @Test
    void aPlayerWhoCannotFightIsToldStraightAway() {
        assertEquals(WorldBossRules.Step.PAUSE_OPTED_OUT,
                WorldBossRules.nextStep(false, true, true, true, false, true, false));
        assertEquals(WorldBossRules.Step.PAUSE_OFFLINE,
                WorldBossRules.nextStep(false, false, false, true, false, true, false));
        assertEquals(WorldBossRules.Step.PAUSE_OUT_OF_RANGE,
                WorldBossRules.nextStep(false, false, true, false, false, true, false));
        assertEquals(WorldBossRules.Step.PAUSE_BUSY,
                WorldBossRules.nextStep(false, false, true, true, true, true, false));
        assertEquals(WorldBossRules.Step.PAUSE_NO_POKEMON,
                WorldBossRules.nextStep(false, false, true, true, false, false, true));
    }

    @Test
    void aFaintedPartyIsGivenItsBreatherBeforeBeingToldItCannotFight() {
        assertEquals(WorldBossRules.Step.WAIT,
                WorldBossRules.nextStep(false, false, true, true, false, false, false));
    }

    @Test
    void autoStartNeedsAnOpenWindowNoBossAndTheGapPassed() {
        WorldBossConfig on = WorldBossConfig.fromJson(json("{\"enabled\":true}"));
        long gap = on.spawnIntervalMinutes() * 60_000L;
        assertTrue(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.OPEN, false, 0L, gap));
        assertFalse(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.OPEN, false, 0L, gap - 1));
        assertFalse(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.OPEN, true, 0L, gap * 2));
        assertFalse(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.BEFORE, false, 0L, gap * 2));
        assertFalse(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.AFTER, false, 0L, gap * 2));
        assertFalse(WorldBossRules.shouldAutoStart(on, WorldBossRules.Window.MANUAL, false, 0L, gap * 2),
                "a window-less config only starts by command");
        assertFalse(WorldBossRules.shouldAutoStart(WorldBossConfig.defaults(), WorldBossRules.Window.OPEN, false, 0L, gap * 2),
                "disabled never starts");
    }

    @Test
    void autoStartOffMeansTheBossOnlyStartsByCommandOrFromAnotherMod() {
        WorldBossConfig off = WorldBossConfig.fromJson(json("{\"enabled\":true,\"spawn\":{\"auto_start\":false}}"));
        assertFalse(off.autoStart());
        long gap = off.spawnIntervalMinutes() * 60_000L;
        assertFalse(WorldBossRules.shouldAutoStart(off, WorldBossRules.Window.OPEN, false, 0L, gap * 2), "never by itself, even in an open window");
        WorldBossConfig on = WorldBossConfig.fromJson(json("{\"enabled\":true}"));
        assertTrue(on.autoStart(), "unchanged default: it starts by itself in the window");
        assertTrue(WorldBossConfig.fromJson(off.toJson()).equals(off), "the setting round-trips through the written file");
        assertFalse(WorldBossConfig.fromJson(off.toJson()).autoStart());
    }

    @Test
    void aBossLeavesAfterItsLifetimeButNeverPastTheWindow() {
        WorldBossConfig config = WorldBossConfig.fromJson(json(
                "{\"window\":{\"end\":\"2026-11-03T00:00:00Z\"},\"spawn\":{\"lifetime_minutes\":40}}"));
        long start = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();
        assertEquals(start + 40 * 60_000L, WorldBossRules.endMillis(config, start));
        long late = Instant.parse("2026-11-02T23:50:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-11-03T00:00:00Z").toEpochMilli(), WorldBossRules.endMillis(config, late));
    }

    @Test
    void coordinateHintsRoundToTheNearestHundred() {
        assertEquals(100, WorldBossRules.coordinateHint(149));
        assertEquals(200, WorldBossRules.coordinateHint(150));
        assertEquals(0, WorldBossRules.coordinateHint(49));
        assertEquals(-100, WorldBossRules.coordinateHint(-149));
        assertEquals(-200, WorldBossRules.coordinateHint(-150));
    }

    @Test
    void theDefaultBossesAreTheOwnersFive() {
        assertEquals(java.util.List.of("cobbleraids:marshadow", "cobbleraids:gengar", "cobbleraids:chandelure",
                "cobbleraids:giratina", "cobbleraids:darkrai"), WorldBossConfig.defaults().raidDefinitions());
    }

    @Test
    void theOlderSingleDefinitionKeyIsStillRead() {
        WorldBossConfig config = WorldBossConfig.fromJson(json("{\"raid_definition\":\"cobbleraids:darkrai\"}"));
        assertEquals(java.util.List.of("cobbleraids:darkrai"), config.raidDefinitions());
    }

    @Test
    void anEmptyOrBadBossListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"raid_definitions\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"raid_definitions\":[\"\"]}")));
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"raid_definitions\":\"x\"}")));
    }

    @Test
    void theNextBossIsNeverTheLastWhenThereIsAChoice() {
        java.util.List<String> options = java.util.List.of("a", "b", "c");
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            assertFalse("b".equals(WorldBossRules.pickDefinition(options, "b", random)));
        }
    }

    @Test
    void everyBossGetsPickedEventually() {
        java.util.List<String> options = java.util.List.of("a", "b", "c");
        java.util.Random random = new java.util.Random(3);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(WorldBossRules.pickDefinition(options, null, random));
        assertEquals(java.util.Set.copyOf(options), seen);
    }

    @Test
    void aLoneBossRepeatsAndNoBossGivesNull() {
        assertEquals("a", WorldBossRules.pickDefinition(java.util.List.of("a"), "a", new java.util.Random(1)));
        assertEquals(null, WorldBossRules.pickDefinition(java.util.List.of(), "a", new java.util.Random(1)));
    }

    @Test
    void theBossIsHugeAndTheWallIsAmethystAtHalfOpacityByDefault() {
        WorldBossConfig defaults = WorldBossConfig.defaults();
        assertEquals(8f, defaults.bossScale());
        assertEquals(3f, defaults.duelBossScale());
        assertEquals("#9A5CC6", defaults.wallColor());
        assertEquals(50, defaults.wallOpacityPercent());
        assertEquals(0x809A5CC6, defaults.wallColorArgb(), "50% opacity is alpha 0x80");
    }

    @Test
    void visualSettingsAreReadValidatedAndRoundTrip() {
        WorldBossConfig config = WorldBossConfig.fromJson(json(
                "{\"visual\":{\"boss_scale\":12.5,\"duel_boss_scale\":1,\"wall_color\":\"#00ff7f\",\"wall_opacity_percent\":100}}"));
        assertEquals(12.5f, config.bossScale());
        assertEquals(0xFF00FF7F, config.wallColorArgb());
        assertEquals(config, WorldBossConfig.fromJson(config.toJson()));
        assertEquals(0x009A5CC6, WorldBossConfig.fromJson(json("{\"visual\":{\"wall_opacity_percent\":0}}")).wallColorArgb());
        for (String bad : new String[] {"{\"visual\":{\"boss_scale\":0}}", "{\"visual\":{\"boss_scale\":31}}", "{\"visual\":{\"duel_boss_scale\":-1}}",
                "{\"visual\":{\"wall_color\":\"purple\"}}", "{\"visual\":{\"wall_color\":\"#12345\"}}", "{\"visual\":{\"wall_opacity_percent\":101}}"}) {
            assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json(bad)), bad);
        }
    }

    @Test
    void theZoneWallIsOnByDefaultAndItsReachIsBounded() {
        WorldBossConfig defaults = WorldBossConfig.defaults();
        assertTrue(defaults.borderParticles());
        assertEquals(40, defaults.borderViewDistance());
        WorldBossConfig off = WorldBossConfig.fromJson(json("{\"visual\":{\"border_particles\":false,\"view_distance\":64}}"));
        assertFalse(off.borderParticles());
        assertEquals(64, off.borderViewDistance());
        assertEquals(off, WorldBossConfig.fromJson(off.toJson()));
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"visual\":{\"view_distance\":8}}")));
        assertThrows(IllegalArgumentException.class, () -> WorldBossConfig.fromJson(json("{\"visual\":{\"view_distance\":200}}")));
    }
}
