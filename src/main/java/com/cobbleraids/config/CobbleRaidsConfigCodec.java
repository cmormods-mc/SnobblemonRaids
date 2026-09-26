package com.cobbleraids.config;

import com.google.gson.JsonObject;

/**
 * JSON marshalling for {@link CobbleRaidsConfig}, split out so the config type itself stays what it
 * is meant to be -- a schema with self-validating nested records -- rather than growing a matching
 * field-by-field read and write block for every setting it gains. {@code CobbleRaidsConfig.fromJson}
 * and {@code toJson} delegate here.
 */
final class CobbleRaidsConfigCodec {

    private CobbleRaidsConfigCodec() {}

    static CobbleRaidsConfig fromJson(JsonObject root) {
        CobbleRaidsConfig defaults = CobbleRaidsConfig.defaults();

        JsonObject natural = Json.object(root, "natural_spawning");
        CobbleRaidsConfig.NaturalSpawning nd = defaults.naturalSpawning();
        JsonObject tierObject = Json.object(natural, "tier_weights");
        RaidTierWeights tw = nd.tierWeights();
        RaidTierWeights tierWeights = new RaidTierWeights(
                Json.integer(tierObject, "starter", tw.starter()),
                Json.integer(tierObject, "powerhouse", tw.powerhouse()),
                Json.integer(tierObject, "legendary", tw.legendary()),
                Json.integer(tierObject, "mythical", tw.mythical())
        );
        JsonObject chanceObject = Json.object(natural, "tier_spawn_chance");
        RaidTierSpawnChance tc = nd.tierSpawnChance();
        RaidTierSpawnChance tierSpawnChance = new RaidTierSpawnChance(
                Json.decimal(chanceObject, "starter", tc.starter()),
                Json.decimal(chanceObject, "powerhouse", tc.powerhouse()),
                Json.decimal(chanceObject, "legendary", tc.legendary()),
                Json.decimal(chanceObject, "mythical", tc.mythical())
        );
        CobbleRaidsConfig.NaturalSpawning naturalSpawning = new CobbleRaidsConfig.NaturalSpawning(
                Json.bool(natural, "enabled", nd.enabled()),
                Json.integer(natural, "check_interval_ticks", nd.checkIntervalTicks()),
                Json.decimal(natural, "spawn_attempt_chance", nd.spawnAttemptChance()),
                Json.integer(natural, "attempts_per_check", nd.attemptsPerCheck()),
                Json.integer(natural, "max_active_raids", nd.maxActiveRaids()),
                Json.integer(natural, "max_active_raids_per_dimension", nd.maxActiveRaidsPerDimension()),
                Json.decimal(natural, "min_distance_from_player", nd.minDistanceFromPlayer()),
                Json.decimal(natural, "max_distance_from_player", nd.maxDistanceFromPlayer()),
                Json.decimal(natural, "min_distance_between_raids", nd.minDistanceBetweenRaids()),
                Json.integer(natural, "location_attempts", nd.locationAttempts()),
                Json.decimal(natural, "despawn_player_radius", nd.despawnPlayerRadius()),
                Json.integer(natural, "default_despawn_seconds", nd.defaultDespawnSeconds()),
                Json.integer(natural, "default_max_lifetime_seconds", nd.defaultMaxLifetimeSeconds()),
                Json.integer(natural, "default_definition_cooldown_seconds", nd.defaultDefinitionCooldownSeconds()),
                tierWeights,
                tierSpawnChance,
                natural.has("announcement_precision")
                        ? CobbleRaidsConfig.AnnouncementPrecision.parse(natural.get("announcement_precision").getAsString())
                        : nd.announcementPrecision()
        );

        JsonObject recruitment = Json.object(root, "recruitment_defaults");
        CobbleRaidsConfig.RecruitmentDefaults rd = defaults.recruitmentDefaults();
        CobbleRaidsConfig.RecruitmentDefaults recruitmentDefaults = new CobbleRaidsConfig.RecruitmentDefaults(
                Json.integer(recruitment, "duration_seconds", rd.durationSeconds()),
                Json.decimal(recruitment, "radius", rd.radius()),
                Json.integer(recruitment, "max_players", rd.maxPlayers())
        );

        JsonObject combat = Json.object(root, "combat_defaults");
        CobbleRaidsConfig.CombatDefaults cd = defaults.combatDefaults();
        CobbleRaidsConfig.CombatDefaults combatDefaults = new CobbleRaidsConfig.CombatDefaults(
                Json.integer(combat, "time_limit_seconds", cd.timeLimitSeconds()),
                Json.bool(combat, "allow_flee", cd.allowFlee()),
                Json.integer(combat, "max_failed_attempts", cd.maxFailedAttempts())
        );

        JsonObject carryover = Json.object(root, "battle_carryover");
        CobbleRaidsConfig.BattleCarryover bc = defaults.battleCarryover();
        CobbleRaidsConfig.BattleCarryover battleCarryover = new CobbleRaidsConfig.BattleCarryover(
                Json.bool(carryover, "health", bc.health()),
                Json.bool(carryover, "pp", bc.pp()),
                Json.bool(carryover, "status", bc.status())
        );

        JsonObject bossTraitsObject = Json.object(root, "boss_traits");
        CobbleRaidsConfig.BossTraits bt = defaults.bossTraits();
        CobbleRaidsConfig.BossTraits bossTraits = new CobbleRaidsConfig.BossTraits(
                Json.integer(bossTraitsObject, "iv_jitter", bt.ivJitter()),
                Json.decimal(bossTraitsObject, "shiny_chance", bt.shinyChance())
        );

        JsonObject catchingObject = Json.object(root, "catching");
        CobbleRaidsConfig.Catching cat = defaults.catching();
        CobbleRaidsConfig.Catching catching = new CobbleRaidsConfig.Catching(
                Json.bool(catchingObject, "enabled", cat.enabled()),
                Json.decimal(catchingObject, "starter", cat.starter()),
                Json.decimal(catchingObject, "powerhouse", cat.powerhouse()),
                Json.decimal(catchingObject, "legendary", cat.legendary()),
                Json.decimal(catchingObject, "mythical", cat.mythical())
        );

        JsonObject currencyObject = Json.object(root, "currency");
        CobbleRaidsConfig.Currency cur = defaults.currency();
        CobbleRaidsConfig.Currency currency = new CobbleRaidsConfig.Currency(
                Json.bool(currencyObject, "enabled", cur.enabled()),
                Json.integer64(currencyObject, "starter", cur.starter()),
                Json.integer64(currencyObject, "powerhouse", cur.powerhouse()),
                Json.integer64(currencyObject, "legendary", cur.legendary()),
                Json.integer64(currencyObject, "mythical", cur.mythical()),
                Json.bool(currencyObject, "scale_with_contribution", cur.scaleWithContribution()),
                Json.decimal(currencyObject, "minimum_share_percentage", cur.minimumSharePercentage())
        );

        JsonObject dynamicLevelObject = Json.object(root, "dynamic_level");
        CobbleRaidsConfig.DynamicLevel dl = defaults.dynamicLevel();
        CobbleRaidsConfig.DynamicLevel dynamicLevel = new CobbleRaidsConfig.DynamicLevel(
                Json.bool(dynamicLevelObject, "enabled", dl.enabled()),
                Json.integer(dynamicLevelObject, "level_offset", dl.levelOffset()),
                Json.integer(dynamicLevelObject, "max_level", dl.maxLevel()));

        JsonObject megaPityObject = Json.object(root, "mega_pity");
        CobbleRaidsConfig.MegaPity mp = defaults.megaPity();
        CobbleRaidsConfig.MegaPity megaPity = new CobbleRaidsConfig.MegaPity(
                Json.bool(megaPityObject, "enabled", mp.enabled()),
                Json.integer(megaPityObject, "threshold", mp.threshold()));

        JsonObject raidPointsObject = Json.object(root, "raid_points");
        CobbleRaidsConfig.RaidPoints rp = defaults.raidPoints();
        CobbleRaidsConfig.RaidPoints raidPoints = new CobbleRaidsConfig.RaidPoints(
                Json.bool(raidPointsObject, "enabled", rp.enabled()),
                Json.integer(raidPointsObject, "starter", rp.starter()),
                Json.integer(raidPointsObject, "powerhouse", rp.powerhouse()),
                Json.integer(raidPointsObject, "legendary", rp.legendary()),
                Json.integer(raidPointsObject, "mythical", rp.mythical()));

        JsonObject personalBossShopObject = Json.object(root, "personal_boss_shop");
        CobbleRaidsConfig.PersonalBossShop pbs = defaults.personalBossShop();
        CobbleRaidsConfig.PersonalBossShop personalBossShop = new CobbleRaidsConfig.PersonalBossShop(
                Json.bool(personalBossShopObject, "enabled", pbs.enabled()),
                Json.integer(personalBossShopObject, "reroll_cost", pbs.rerollCost()),
                Json.integer(personalBossShopObject, "buy_cost_starter", pbs.buyCostStarter()),
                Json.integer(personalBossShopObject, "buy_cost_powerhouse", pbs.buyCostPowerhouse()),
                Json.integer(personalBossShopObject, "buy_cost_legendary", pbs.buyCostLegendary()),
                Json.integer(personalBossShopObject, "buy_cost_mythical", pbs.buyCostMythical()));

        JsonObject tierScalingObject = Json.object(root, "tier_scaling");
        CobbleRaidsConfig.TierScaling ts = defaults.tierScaling();
        CobbleRaidsConfig.TierScaling tierScaling = new CobbleRaidsConfig.TierScaling(
                Json.bool(tierScalingObject, "enabled", ts.enabled()),
                readTierMultipliers(tierScalingObject, "starter", ts.starter()),
                readTierMultipliers(tierScalingObject, "powerhouse", ts.powerhouse()),
                readTierMultipliers(tierScalingObject, "legendary", ts.legendary()),
                readTierMultipliers(tierScalingObject, "mythical", ts.mythical())
        );

        JsonObject bossGlowObject = Json.object(root, "boss_glow");
        CobbleRaidsConfig.BossGlow bg = defaults.bossGlow();
        CobbleRaidsConfig.BossGlow bossGlow = new CobbleRaidsConfig.BossGlow(
                Json.bool(bossGlowObject, "enabled", bg.enabled()),
                Json.decimal(bossGlowObject, "radius_blocks", bg.radiusBlocks())
        );

        JsonObject bossMovementObject = Json.object(root, "boss_movement");
        CobbleRaidsConfig.BossMovement bm = defaults.bossMovement();
        CobbleRaidsConfig.BossMovement bossMovement = new CobbleRaidsConfig.BossMovement(
                Json.bool(bossMovementObject, "slowness_enabled", bm.slownessEnabled()),
                Json.integer(bossMovementObject, "slowness_amplifier", bm.slownessAmplifier()),
                Json.bool(bossMovementObject, "prevent_knockback", bm.preventKnockback()));

        JsonObject renownObject = Json.object(root, "renown");
        JsonObject renownChance = Json.object(renownObject, "chance");
        CobbleRaidsConfig.Renown rn = defaults.renown();
        CobbleRaidsConfig.Renown renown = new CobbleRaidsConfig.Renown(
                Json.bool(renownObject, "enabled", rn.enabled()),
                Json.decimal(renownChance, "starter", rn.starter()),
                Json.decimal(renownChance, "powerhouse", rn.powerhouse()),
                Json.decimal(renownChance, "legendary", rn.legendary()),
                Json.decimal(renownChance, "mythical", rn.mythical()),
                Json.decimal(renownObject, "health_bonus", rn.healthBonus()),
                Json.integer(renownObject, "stat_focus_evs", rn.statFocusEvs()),
                Json.decimal(renownObject, "points_multiplier", rn.pointsMultiplier()),
                Json.decimal(renownObject, "currency_multiplier", rn.currencyMultiplier()));

        JsonObject reconnectGraceObject = Json.object(root, "reconnect_grace");
        CobbleRaidsConfig.ReconnectGrace rg = defaults.reconnectGrace();
        CobbleRaidsConfig.ReconnectGrace reconnectGrace = new CobbleRaidsConfig.ReconnectGrace(
                Json.bool(reconnectGraceObject, "enabled", rg.enabled()),
                Json.integer(reconnectGraceObject, "grace_seconds", rg.graceSeconds()));

        return new CobbleRaidsConfig(naturalSpawning, recruitmentDefaults, combatDefaults, battleCarryover,
                bossTraits, catching, currency, dynamicLevel, megaPity, raidPoints, personalBossShop, tierScaling,
                bossGlow, bossMovement, renown, reconnectGrace, Json.bool(root, "debug_logging", defaults.debugLogging()));
    }

    private static CobbleRaidsConfig.TierMultipliers readTierMultipliers(
            JsonObject tierScaling, String key, CobbleRaidsConfig.TierMultipliers fallback) {
        JsonObject tier = Json.object(tierScaling, key);
        return new CobbleRaidsConfig.TierMultipliers(
                Json.decimal(tier, "health_multiplier", fallback.health()),
                Json.decimal(tier, "time_limit_multiplier", fallback.timeLimit()),
                Json.decimal(tier, "reward_multiplier", fallback.reward())
        );
    }

    static JsonObject toJson(CobbleRaidsConfig config) {
        JsonObject root = new JsonObject();
        CobbleRaidsConfig.NaturalSpawning naturalSpawning = config.naturalSpawning();
        JsonObject natural = new JsonObject();
        natural.addProperty("enabled", naturalSpawning.enabled());
        natural.addProperty("check_interval_ticks", naturalSpawning.checkIntervalTicks());
        natural.addProperty("spawn_attempt_chance", naturalSpawning.spawnAttemptChance());
        natural.addProperty("attempts_per_check", naturalSpawning.attemptsPerCheck());
        natural.addProperty("max_active_raids", naturalSpawning.maxActiveRaids());
        natural.addProperty("max_active_raids_per_dimension", naturalSpawning.maxActiveRaidsPerDimension());
        natural.addProperty("min_distance_from_player", naturalSpawning.minDistanceFromPlayer());
        natural.addProperty("max_distance_from_player", naturalSpawning.maxDistanceFromPlayer());
        natural.addProperty("min_distance_between_raids", naturalSpawning.minDistanceBetweenRaids());
        natural.addProperty("location_attempts", naturalSpawning.locationAttempts());
        natural.addProperty("despawn_player_radius", naturalSpawning.despawnPlayerRadius());
        natural.addProperty("default_despawn_seconds", naturalSpawning.defaultDespawnSeconds());
        natural.addProperty("default_max_lifetime_seconds", naturalSpawning.defaultMaxLifetimeSeconds());
        natural.addProperty("default_definition_cooldown_seconds", naturalSpawning.defaultDefinitionCooldownSeconds());
        JsonObject tierWeights = new JsonObject();
        tierWeights.addProperty("starter", naturalSpawning.tierWeights().starter());
        tierWeights.addProperty("powerhouse", naturalSpawning.tierWeights().powerhouse());
        tierWeights.addProperty("legendary", naturalSpawning.tierWeights().legendary());
        tierWeights.addProperty("mythical", naturalSpawning.tierWeights().mythical());
        natural.add("tier_weights", tierWeights);
        JsonObject tierSpawnChance = new JsonObject();
        tierSpawnChance.addProperty("starter", naturalSpawning.tierSpawnChance().starter());
        tierSpawnChance.addProperty("powerhouse", naturalSpawning.tierSpawnChance().powerhouse());
        tierSpawnChance.addProperty("legendary", naturalSpawning.tierSpawnChance().legendary());
        tierSpawnChance.addProperty("mythical", naturalSpawning.tierSpawnChance().mythical());
        natural.add("tier_spawn_chance", tierSpawnChance);
        natural.addProperty("announcement_precision", naturalSpawning.announcementPrecision().serializedName());
        root.add("natural_spawning", natural);

        CobbleRaidsConfig.RecruitmentDefaults recruitmentDefaults = config.recruitmentDefaults();
        JsonObject recruitment = new JsonObject();
        recruitment.addProperty("duration_seconds", recruitmentDefaults.durationSeconds());
        recruitment.addProperty("radius", recruitmentDefaults.radius());
        recruitment.addProperty("max_players", recruitmentDefaults.maxPlayers());
        root.add("recruitment_defaults", recruitment);

        CobbleRaidsConfig.CombatDefaults combatDefaults = config.combatDefaults();
        JsonObject combat = new JsonObject();
        combat.addProperty("time_limit_seconds", combatDefaults.timeLimitSeconds());
        combat.addProperty("allow_flee", combatDefaults.allowFlee());
        combat.addProperty("max_failed_attempts", combatDefaults.maxFailedAttempts());
        root.add("combat_defaults", combat);

        CobbleRaidsConfig.BattleCarryover battleCarryover = config.battleCarryover();
        JsonObject carryoverObject = new JsonObject();
        carryoverObject.addProperty("health", battleCarryover.health());
        carryoverObject.addProperty("pp", battleCarryover.pp());
        carryoverObject.addProperty("status", battleCarryover.status());
        root.add("battle_carryover", carryoverObject);

        CobbleRaidsConfig.BossTraits bossTraits = config.bossTraits();
        JsonObject bossTraitsJson = new JsonObject();
        bossTraitsJson.addProperty("iv_jitter", bossTraits.ivJitter());
        bossTraitsJson.addProperty("shiny_chance", bossTraits.shinyChance());
        root.add("boss_traits", bossTraitsJson);

        CobbleRaidsConfig.Catching catching = config.catching();
        JsonObject catchingJson = new JsonObject();
        catchingJson.addProperty("enabled", catching.enabled());
        catchingJson.addProperty("starter", catching.starter());
        catchingJson.addProperty("powerhouse", catching.powerhouse());
        catchingJson.addProperty("legendary", catching.legendary());
        catchingJson.addProperty("mythical", catching.mythical());
        root.add("catching", catchingJson);

        CobbleRaidsConfig.Currency currency = config.currency();
        JsonObject currencyJson = new JsonObject();
        currencyJson.addProperty("enabled", currency.enabled());
        currencyJson.addProperty("starter", currency.starter());
        currencyJson.addProperty("powerhouse", currency.powerhouse());
        currencyJson.addProperty("legendary", currency.legendary());
        currencyJson.addProperty("mythical", currency.mythical());
        currencyJson.addProperty("scale_with_contribution", currency.scaleWithContribution());
        currencyJson.addProperty("minimum_share_percentage", currency.minimumSharePercentage());
        root.add("currency", currencyJson);

        CobbleRaidsConfig.DynamicLevel dynamicLevel = config.dynamicLevel();
        JsonObject dynamicLevelJson = new JsonObject();
        dynamicLevelJson.addProperty("enabled", dynamicLevel.enabled());
        dynamicLevelJson.addProperty("level_offset", dynamicLevel.levelOffset());
        dynamicLevelJson.addProperty("max_level", dynamicLevel.maxLevel());
        root.add("dynamic_level", dynamicLevelJson);

        CobbleRaidsConfig.MegaPity megaPity = config.megaPity();
        JsonObject megaPityJson = new JsonObject();
        megaPityJson.addProperty("enabled", megaPity.enabled());
        megaPityJson.addProperty("threshold", megaPity.threshold());
        root.add("mega_pity", megaPityJson);

        CobbleRaidsConfig.RaidPoints raidPoints = config.raidPoints();
        JsonObject raidPointsJson = new JsonObject();
        raidPointsJson.addProperty("enabled", raidPoints.enabled());
        raidPointsJson.addProperty("starter", raidPoints.starter());
        raidPointsJson.addProperty("powerhouse", raidPoints.powerhouse());
        raidPointsJson.addProperty("legendary", raidPoints.legendary());
        raidPointsJson.addProperty("mythical", raidPoints.mythical());
        root.add("raid_points", raidPointsJson);

        CobbleRaidsConfig.PersonalBossShop personalBossShop = config.personalBossShop();
        JsonObject personalBossShopJson = new JsonObject();
        personalBossShopJson.addProperty("enabled", personalBossShop.enabled());
        personalBossShopJson.addProperty("reroll_cost", personalBossShop.rerollCost());
        personalBossShopJson.addProperty("buy_cost_starter", personalBossShop.buyCostStarter());
        personalBossShopJson.addProperty("buy_cost_powerhouse", personalBossShop.buyCostPowerhouse());
        personalBossShopJson.addProperty("buy_cost_legendary", personalBossShop.buyCostLegendary());
        personalBossShopJson.addProperty("buy_cost_mythical", personalBossShop.buyCostMythical());
        root.add("personal_boss_shop", personalBossShopJson);

        CobbleRaidsConfig.TierScaling tierScaling = config.tierScaling();
        JsonObject tierScalingObject = new JsonObject();
        tierScalingObject.addProperty("enabled", tierScaling.enabled());
        tierScalingObject.add("starter", tierMultipliersJson(tierScaling.starter()));
        tierScalingObject.add("powerhouse", tierMultipliersJson(tierScaling.powerhouse()));
        tierScalingObject.add("legendary", tierMultipliersJson(tierScaling.legendary()));
        tierScalingObject.add("mythical", tierMultipliersJson(tierScaling.mythical()));
        root.add("tier_scaling", tierScalingObject);

        CobbleRaidsConfig.BossGlow bossGlow = config.bossGlow();
        JsonObject bossGlowObject = new JsonObject();
        bossGlowObject.addProperty("enabled", bossGlow.enabled());
        bossGlowObject.addProperty("radius_blocks", bossGlow.radiusBlocks());
        root.add("boss_glow", bossGlowObject);

        CobbleRaidsConfig.BossMovement bossMovement = config.bossMovement();
        JsonObject bossMovementJson = new JsonObject();
        bossMovementJson.addProperty("slowness_enabled", bossMovement.slownessEnabled());
        bossMovementJson.addProperty("slowness_amplifier", bossMovement.slownessAmplifier());
        bossMovementJson.addProperty("prevent_knockback", bossMovement.preventKnockback());
        root.add("boss_movement", bossMovementJson);

        CobbleRaidsConfig.Renown renown = config.renown();
        JsonObject renownJson = new JsonObject();
        renownJson.addProperty("enabled", renown.enabled());
        JsonObject renownChanceJson = new JsonObject();
        renownChanceJson.addProperty("starter", renown.starter());
        renownChanceJson.addProperty("powerhouse", renown.powerhouse());
        renownChanceJson.addProperty("legendary", renown.legendary());
        renownChanceJson.addProperty("mythical", renown.mythical());
        renownJson.add("chance", renownChanceJson);
        renownJson.addProperty("health_bonus", renown.healthBonus());
        renownJson.addProperty("stat_focus_evs", renown.statFocusEvs());
        renownJson.addProperty("points_multiplier", renown.pointsMultiplier());
        renownJson.addProperty("currency_multiplier", renown.currencyMultiplier());
        root.add("renown", renownJson);

        CobbleRaidsConfig.ReconnectGrace reconnectGrace = config.reconnectGrace();
        JsonObject reconnectGraceJson = new JsonObject();
        reconnectGraceJson.addProperty("enabled", reconnectGrace.enabled());
        reconnectGraceJson.addProperty("grace_seconds", reconnectGrace.graceSeconds());
        root.add("reconnect_grace", reconnectGraceJson);

        root.addProperty("debug_logging", config.debugLogging());
        return root;
    }

    private static JsonObject tierMultipliersJson(CobbleRaidsConfig.TierMultipliers multipliers) {
        JsonObject object = new JsonObject();
        object.addProperty("health_multiplier", multipliers.health());
        object.addProperty("time_limit_multiplier", multipliers.timeLimit());
        object.addProperty("reward_multiplier", multipliers.reward());
        return object;
    }
}
