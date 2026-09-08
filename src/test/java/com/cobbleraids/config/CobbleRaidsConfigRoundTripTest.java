package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CobbleRaidsConfigManager now rewrites server.json in its canonical form whenever the file differs
 * from what this build serializes, so an existing server gains settings added in a new version
 * instead of never seeing them. That makes the round trip load-bearing: a field that
 * {@code toJson} forgets would be dropped from a live operator's config the next time the server
 * starts, silently reverting it to a default. These tests are what stop that.
 */
class CobbleRaidsConfigRoundTripTest {

    @Test
    @DisplayName("defaults survive a serialize/parse round trip unchanged")
    void defaultsRoundTrip() {
        CobbleRaidsConfig defaults = CobbleRaidsConfig.defaults();

        CobbleRaidsConfig reparsed = CobbleRaidsConfig.fromJson(defaults.toJson());

        assertEquals(defaults, reparsed);
    }

    @Test
    @DisplayName("serialization is stable, so the migration write cannot loop")
    void serializationIsStable() {
        // If toJson(fromJson(x)) != x, the manager would rewrite the file on every single start.
        JsonObject once = CobbleRaidsConfig.defaults().toJson();
        JsonObject twice = CobbleRaidsConfig.fromJson(once).toJson();

        assertEquals(once, twice);
    }

    @Test
    @DisplayName("a non-default value survives the round trip rather than reverting")
    void customValuesSurvive() {
        // The failure this guards is an operator's tuning being quietly reset by the migration.
        CobbleRaidsConfig custom = new CobbleRaidsConfig(
                CobbleRaidsConfig.defaults().naturalSpawning(),
                new CobbleRaidsConfig.RecruitmentDefaults(45, 12.5, 3),
                new CobbleRaidsConfig.CombatDefaults(600, true, 7),
                new CobbleRaidsConfig.BattleCarryover(false, true, true),
                new CobbleRaidsConfig.BossTraits(5, 0.25),
                CobbleRaidsConfig.defaults().tierScaling(),
                CobbleRaidsConfig.defaults().bossGlow(),
                CobbleRaidsConfig.defaults().bossMovement(),
                true);

        CobbleRaidsConfig reparsed = CobbleRaidsConfig.fromJson(custom.toJson());

        assertEquals(custom, reparsed);
        assertEquals(7, reparsed.combatDefaults().maxFailedAttempts());
        assertFalse(reparsed.battleCarryover().health());
        assertTrue(reparsed.battleCarryover().status());
        assertEquals(5, reparsed.bossTraits().ivJitter());
        assertEquals(0.25, reparsed.bossTraits().shinyChance(), 0.0);
    }

    @Test
    @DisplayName("an older config without the new blocks loads on defaults, not on zeroes")
    void olderConfigGainsDefaults() {
        // Exactly what an existing server.json looks like before the upgrade: both new blocks
        // absent. Reading them as false/0 would silently disable the feature or, worse, set an
        // attempt cap of zero.
        JsonObject old = CobbleRaidsConfig.defaults().toJson();
        old.remove("battle_carryover");
        old.remove("boss_traits");
        old.getAsJsonObject("combat_defaults").remove("max_failed_attempts");

        CobbleRaidsConfig loaded = CobbleRaidsConfig.fromJson(old);

        assertEquals(CobbleRaidsConfig.defaults().battleCarryover(), loaded.battleCarryover());
        assertEquals(CobbleRaidsConfig.defaults().combatDefaults().maxFailedAttempts(),
                loaded.combatDefaults().maxFailedAttempts());
        assertEquals(CobbleRaidsConfig.defaults().bossTraits(), loaded.bossTraits());
    }

    @Test
    @DisplayName("an entirely empty config is the default config")
    void emptyConfigIsDefaults() {
        assertEquals(CobbleRaidsConfig.defaults(), CobbleRaidsConfig.fromJson(new JsonObject()));
    }

    @Test
    @DisplayName("carryover defaults: health and PP on, status off")
    void carryoverDefaults() {
        CobbleRaidsConfig.BattleCarryover carryover = CobbleRaidsConfig.defaults().battleCarryover();

        assertTrue(carryover.health(), "health carryover should be on by default");
        assertTrue(carryover.pp(), "pp carryover should be on by default");
        assertFalse(carryover.status(), "status carryover should be off by default");
        assertFalse(carryover.isNoOp());
    }

    @Test
    @DisplayName("all-off carryover reports itself as a no-op so the pass can be skipped")
    void allOffIsNoOp() {
        assertTrue(new CobbleRaidsConfig.BattleCarryover(false, false, false).isNoOp());
        assertFalse(new CobbleRaidsConfig.BattleCarryover(false, false, true).isNoOp());
    }

    @Test
    @DisplayName("max_failed_attempts of 0 means unlimited, not 'remove on first loss'")
    void zeroAttemptsMeansUnlimited() {
        assertFalse(new CobbleRaidsConfig.CombatDefaults(900, false, 0).attemptsAreLimited());
        assertTrue(new CobbleRaidsConfig.CombatDefaults(900, false, 1).attemptsAreLimited());
    }

    @Test
    @DisplayName("a negative attempt cap is rejected rather than making bosses immortal")
    void negativeAttemptCapIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CombatDefaults(900, false, -1));
    }
}
