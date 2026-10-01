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
                new CobbleRaidsConfig.Catching(true,
                        new CobbleRaidsConfig.CaptureTierConfig(0.5, 0.1, 0.1, 1000, 200, 80, 900, 180, 70),
                        new CobbleRaidsConfig.CaptureTierConfig(0.25, 0.05, 0.05, 1000, 200, 80, 900, 180, 70),
                        new CobbleRaidsConfig.CaptureTierConfig(0.1, 0.02, 0.02, 1000, 200, 80, 900, 180, 70),
                        new CobbleRaidsConfig.CaptureTierConfig(0.0, 0.0, 0.0, 1000, 200, 80, 900, 180, 70),
                        45, 12, 20, 90),
                new CobbleRaidsConfig.Currency(true, 25L, 100L, 400L, 1_000L, true, 20.0),
                new CobbleRaidsConfig.DynamicLevel(true, -5, 90),
                new CobbleRaidsConfig.MegaPity(true, 20),
                new CobbleRaidsConfig.RaidPoints(true, 10, 20, 30, 40),
                new CobbleRaidsConfig.PersonalBossShop(false, 75, 15, 30, 60, 120),
                CobbleRaidsConfig.defaults().tierScaling(),
                CobbleRaidsConfig.defaults().bossGlow(),
                CobbleRaidsConfig.defaults().bossMovement(),
                new CobbleRaidsConfig.Renown(false, 0.2, 0.3, 0.4, 0.5, 0.25, 64, 1.5, 2.0, 7, 0.4),
                new CobbleRaidsConfig.ReconnectGrace(false, 120),
                true);

        CobbleRaidsConfig reparsed = CobbleRaidsConfig.fromJson(custom.toJson());

        assertEquals(custom, reparsed);
        assertEquals(7, reparsed.combatDefaults().maxFailedAttempts());
        assertFalse(reparsed.battleCarryover().health());
        assertTrue(reparsed.battleCarryover().status());
        assertEquals(5, reparsed.bossTraits().ivJitter());
        assertEquals(0.25, reparsed.bossTraits().shinyChance(), 0.0);
        assertTrue(reparsed.catching().enabled());
        assertEquals(0.5, reparsed.catching().chanceFor(RaidRarityTier.STARTER), 0.0);
        assertEquals(0.0, reparsed.catching().chanceFor(RaidRarityTier.MYTHICAL), 0.0);
        assertEquals(0.7, reparsed.catching().ceilingFor(RaidRarityTier.STARTER), 0.0);
        assertEquals(45, reparsed.catching().choiceWindowSeconds());
        assertEquals(12, reparsed.catching().sequenceTimeoutSeconds());
        assertEquals(20, reparsed.catching().deliveryRetryIntervalSeconds());
        assertEquals(90, reparsed.catching().sessionRetentionSecondsAfterResolve());
        assertTrue(reparsed.currency().enabled());
        assertEquals(25L, reparsed.currency().amountFor(RaidRarityTier.STARTER));
        assertEquals(1_000L, reparsed.currency().amountFor(RaidRarityTier.MYTHICAL));
        assertTrue(reparsed.currency().scaleWithContribution());
        assertEquals(20.0, reparsed.currency().minimumSharePercentage(), 0.0);
        assertEquals(-5, reparsed.dynamicLevel().levelOffset());
        assertEquals(90, reparsed.dynamicLevel().maxLevel());
        assertEquals(20, reparsed.megaPity().threshold());
        assertEquals(30, reparsed.raidPoints().legendary());
        assertFalse(reparsed.personalBossShop().enabled());
        assertEquals(75, reparsed.personalBossShop().rerollCost());
        assertEquals(60, reparsed.personalBossShop().buyCostFor(RaidRarityTier.LEGENDARY));
        assertFalse(reparsed.renown().enabled());
        assertEquals(0.4, reparsed.renown().chanceFor(RaidRarityTier.LEGENDARY), 0.0);
        assertEquals(0.25, reparsed.renown().healthBonus(), 0.0);
        assertEquals(64, reparsed.renown().statFocusEvs());
        assertEquals(2.0, reparsed.renown().currencyMultiplier(), 0.0);
        assertEquals(7, reparsed.renown().levelBonus());
        assertEquals(0.4, reparsed.renown().baseHealthBonus(), 0.0);
        assertFalse(reparsed.reconnectGrace().enabled());
        assertEquals(120, reparsed.reconnectGrace().graceSeconds());
    }

    @Test
    @DisplayName("renown ships on at 5/7/10/12%, and its strengths stay moderate")
    void renownDefaults() {
        CobbleRaidsConfig.Renown renown = CobbleRaidsConfig.defaults().renown();

        assertTrue(renown.enabled());
        assertEquals(0.05, renown.chanceFor(RaidRarityTier.STARTER), 0.0);
        assertEquals(0.07, renown.chanceFor(RaidRarityTier.POWERHOUSE), 0.0);
        assertEquals(0.10, renown.chanceFor(RaidRarityTier.LEGENDARY), 0.0);
        assertEquals(0.12, renown.chanceFor(RaidRarityTier.MYTHICAL), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.6, 128, 1.25, 1.25, 10, 0.3), "health bonus cap");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.15, 253, 1.25, 1.25, 10, 0.3), "EV cap");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.15, 128, 0.9, 1.25, 10, 0.3), "renown never pays less");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 12.0, 0, 0, 0, 0.15, 128, 1.25, 1.25, 10, 0.3), "a chance is not a percentage");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.15, 128, 1.25, 1.25, 51, 0.3), "level bonus cap");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.15, 128, 1.25, 1.25, -1, 0.3), "no negative levels");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.Renown(true, 0, 0, 0, 0, 0.15, 128, 1.25, 1.25, 10, 1.5), "base pool bonus cap");
    }

    @Test
    @DisplayName("renowned bosses ship 10 levels up, with a 30% larger pool and 200-EV focus")
    void renownIsStrongerThanItWas() {
        CobbleRaidsConfig.Renown renown = CobbleRaidsConfig.defaults().renown();

        assertEquals(10, renown.levelBonus());
        assertEquals(0.30, renown.baseHealthBonus(), 0.0);
        assertEquals(200, renown.statFocusEvs());
    }

    @Test
    @DisplayName("an untouched pre-bonus renown block moves to the new strengths; an edited one stays")
    void renownStrengthsMigrateOnlyWhenUntouched() {
        JsonObject untouched = CobbleRaidsConfig.defaults().toJson();
        JsonObject old = untouched.getAsJsonObject("renown");
        old.addProperty("stat_focus_evs", 128);
        old.remove("level_bonus");
        old.remove("base_health_bonus");

        CobbleRaidsConfig.Renown migrated = CobbleRaidsConfig.fromJson(untouched).renown();
        assertEquals(200, migrated.statFocusEvs(), "the old shipped value is replaced");
        assertEquals(10, migrated.levelBonus(), "a key new to this version gets its default");
        assertEquals(0.30, migrated.baseHealthBonus(), 0.0);

        JsonObject edited = CobbleRaidsConfig.defaults().toJson();
        edited.getAsJsonObject("renown").addProperty("stat_focus_evs", 64);
        assertEquals(64, CobbleRaidsConfig.fromJson(edited).renown().statFocusEvs(), "an operator value stays");
    }

    @Test
    @DisplayName("a boss shop still at either earlier shipped price list moves to 500 / 750 / 1000 / 1500; an edited one stays")
    void bossShopPricesMigrateOnlyWhenUntouched() {
        for (CobbleRaidsConfig.PersonalBossShop old : new CobbleRaidsConfig.PersonalBossShop[] {
                CobbleRaidsConfig.PersonalBossShop.supersededDefaults(),
                CobbleRaidsConfig.PersonalBossShop.previousDefaults()}) {
            JsonObject json = CobbleRaidsConfig.defaults().toJson();
            JsonObject block = json.getAsJsonObject("personal_boss_shop");
            block.addProperty("buy_cost_starter", old.buyCostStarter());
            block.addProperty("buy_cost_powerhouse", old.buyCostPowerhouse());
            block.addProperty("buy_cost_legendary", old.buyCostLegendary());
            block.addProperty("buy_cost_mythical", old.buyCostMythical());

            assertEquals(CobbleRaidsConfig.defaults().personalBossShop(), CobbleRaidsConfig.fromJson(json).personalBossShop());
        }

        JsonObject edited = CobbleRaidsConfig.defaults().toJson();
        edited.getAsJsonObject("personal_boss_shop").addProperty("buy_cost_starter", 320);
        assertEquals(320, CobbleRaidsConfig.fromJson(edited).personalBossShop().buyCostStarter());
    }

    @Test
    @DisplayName("an untouched pre-cut currency block moves to the new defaults; an edited one stays")
    void currencyMigratesOnlyWhenUntouched() {
        JsonObject untouched = CobbleRaidsConfig.defaults().toJson();
        var old = new JsonObject();
        old.addProperty("enabled", false);
        old.addProperty("starter", 2_000L);
        old.addProperty("powerhouse", 5_000L);
        old.addProperty("legendary", 12_000L);
        old.addProperty("mythical", 25_000L);
        old.addProperty("scale_with_contribution", true);
        old.addProperty("minimum_share_percentage", 10.0);
        untouched.add("currency", old);
        assertEquals(CobbleRaidsConfig.defaults().currency(), CobbleRaidsConfig.fromJson(untouched).currency());

        JsonObject edited = CobbleRaidsConfig.defaults().toJson();
        old = old.deepCopy();
        old.addProperty("legendary", 13_000L);
        edited.add("currency", old);
        assertEquals(13_000L, CobbleRaidsConfig.fromJson(edited).currency().legendary());
        assertFalse(CobbleRaidsConfig.fromJson(edited).currency().enabled());
    }

    @Test
    @DisplayName("an untouched pre-repricing boss buy-back block moves to the new prices; an edited one stays")
    void bossBuyBackMigratesOnlyWhenUntouched() {
        JsonObject untouched = CobbleRaidsConfig.defaults().toJson();
        var old = new JsonObject();
        old.addProperty("enabled", true);
        old.addProperty("reroll_cost", 50);
        old.addProperty("buy_cost_starter", 40);
        old.addProperty("buy_cost_powerhouse", 80);
        old.addProperty("buy_cost_legendary", 160);
        old.addProperty("buy_cost_mythical", 300);
        untouched.add("personal_boss_shop", old);
        assertEquals(CobbleRaidsConfig.defaults().personalBossShop(),
                CobbleRaidsConfig.fromJson(untouched).personalBossShop());

        JsonObject edited = CobbleRaidsConfig.defaults().toJson();
        old = old.deepCopy();
        old.addProperty("buy_cost_legendary", 170);
        edited.add("personal_boss_shop", old);
        assertEquals(170, CobbleRaidsConfig.fromJson(edited).personalBossShop().buyCostLegendary());
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
        old.remove("catching");
        old.remove("currency");
        old.remove("dynamic_level");
        old.remove("mega_pity");
        old.remove("raid_points");
        old.remove("personal_boss_shop");
        old.remove("renown");
        old.remove("reconnect_grace");
        old.getAsJsonObject("combat_defaults").remove("max_failed_attempts");

        CobbleRaidsConfig loaded = CobbleRaidsConfig.fromJson(old);

        assertEquals(CobbleRaidsConfig.defaults().renown(), loaded.renown());
        assertEquals(CobbleRaidsConfig.defaults().reconnectGrace(), loaded.reconnectGrace());
        assertEquals(CobbleRaidsConfig.defaults().battleCarryover(), loaded.battleCarryover());
        assertEquals(CobbleRaidsConfig.defaults().combatDefaults().maxFailedAttempts(),
                loaded.combatDefaults().maxFailedAttempts());
        assertEquals(CobbleRaidsConfig.defaults().bossTraits(), loaded.bossTraits());
        assertEquals(CobbleRaidsConfig.defaults().catching(), loaded.catching());
        assertEquals(CobbleRaidsConfig.defaults().currency(), loaded.currency());
        assertEquals(CobbleRaidsConfig.defaults().dynamicLevel(), loaded.dynamicLevel());
        assertEquals(CobbleRaidsConfig.defaults().megaPity(), loaded.megaPity());
        assertEquals(CobbleRaidsConfig.defaults().raidPoints(), loaded.raidPoints());
        assertEquals(CobbleRaidsConfig.defaults().personalBossShop(), loaded.personalBossShop());
    }

    @Test
    @DisplayName("personal boss shop ships on, unlike the free catch mechanic it coexists with")
    void personalBossShopDefaults() {
        CobbleRaidsConfig.PersonalBossShop shop = CobbleRaidsConfig.defaults().personalBossShop();

        assertTrue(shop.enabled());
        assertEquals(50, shop.rerollCost());
        assertEquals(500, shop.buyCostFor(RaidRarityTier.STARTER));
        assertEquals(750, shop.buyCostFor(RaidRarityTier.POWERHOUSE));
        assertEquals(1_000, shop.buyCostFor(RaidRarityTier.LEGENDARY));
        assertEquals(1_500, shop.buyCostFor(RaidRarityTier.MYTHICAL));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.PersonalBossShop(true, -1, 40, 80, 160, 300), "reroll cost cannot be negative");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.PersonalBossShop(true, 50, -1, 80, 160, 300), "a buy cost cannot be negative");
    }

    @Test
    @DisplayName("reconnect grace ships on at 5 minutes, bounded 1..3600s")
    void reconnectGraceDefaults() {
        CobbleRaidsConfig.ReconnectGrace grace = CobbleRaidsConfig.defaults().reconnectGrace();

        assertTrue(grace.enabled());
        assertEquals(300, grace.graceSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.ReconnectGrace(true, 0), "must be at least 1 second");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.ReconnectGrace(true, 3601), "must be at most an hour");
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
    @DisplayName("catching ships on with the 8/6/4/2% base rates named in its javadoc")
    void catchingDefaultRates() {
        CobbleRaidsConfig.Catching catching = CobbleRaidsConfig.defaults().catching();

        assertTrue(catching.enabled());
        assertFalse(catching.isNoOp(), "starter through mythical should all be catchable by default");
        assertEquals(0.08, catching.chanceFor(RaidRarityTier.STARTER), 0.0);
        assertEquals(0.06, catching.chanceFor(RaidRarityTier.POWERHOUSE), 0.0);
        assertEquals(0.04, catching.chanceFor(RaidRarityTier.LEGENDARY), 0.0);
        assertEquals(0.02, catching.chanceFor(RaidRarityTier.MYTHICAL), 0.0);
        assertEquals(0.12, catching.ceilingFor(RaidRarityTier.STARTER), 1e-9);
        assertEquals(0.10, catching.ceilingFor(RaidRarityTier.POWERHOUSE), 1e-9);
        assertEquals(0.07, catching.ceilingFor(RaidRarityTier.LEGENDARY), 1e-9);
        assertEquals(0.04, catching.ceilingFor(RaidRarityTier.MYTHICAL), 1e-9);
    }

    @Test
    @DisplayName("a chance above 1 is rejected rather than read as a percentage")
    void catchChanceIsBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CaptureTierConfig(50.0, 0.0, 0.0, 1000, 200, 80, 900, 180, 70));
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CaptureTierConfig(-0.1, 0.0, 0.0, 1000, 200, 80, 900, 180, 70));
    }

    @Test
    @DisplayName("a tier ceiling above 100% is rejected even when each fraction is individually valid")
    void catchCeilingCannotExceedOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CaptureTierConfig(0.6, 0.3, 0.3, 1000, 200, 80, 900, 180, 70),
                "0.6 + 0.3 + 0.3 = 1.2, over the ceiling");
    }

    @Test
    @DisplayName("a zone cannot be wider than the track it's judged against")
    void catchZoneWidthsAreBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CaptureTierConfig(0.1, 0.05, 0.05, 1000, 1200, 80, 900, 180, 70),
                "good zone wider than the pulse's own travel duration");
        assertThrows(IllegalArgumentException.class,
                () -> new CobbleRaidsConfig.CaptureTierConfig(0.1, 0.05, 0.05, 1000, 200, 260, 900, 180, 70),
                "perfect zone wider than its own good zone");
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
