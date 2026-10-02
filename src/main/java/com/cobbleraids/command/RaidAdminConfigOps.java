package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidRewardPolicyManager;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.shop.ShopCatalogManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Lets an operator see the effective config ({@link #show}) and re-read every hand-edited JSON CobbleRaids owns -- server.json, the reward
 * policy, and the shop catalogue -- without a full datapack /reload.
 *
 * <p>The three are independent files with independent failure modes, so each gets its own
 * try/catch: a malformed shop catalogue must not stop the config and reward-policy reloads that
 * already succeeded from taking effect, matching how the three run as three separate
 * START_DATA_PACK_RELOAD listeners in CobbleRaids.java rather than one. This used to only reload
 * CobbleRaidsConfigManager, silently leaving the reward policy and shop catalogue stale -- exactly
 * the gap a full /reload does not have.
 */
final class RaidAdminConfigOps {
    private RaidAdminConfigOps() {}

    static int reload(CommandSourceStack source) {
        boolean configOk = reloadOne(source, "CobbleRaids config", CobbleRaidsConfigManager::reload);
        boolean policyOk = reloadOne(source, "reward policy", RaidRewardPolicyManager::reload);
        boolean catalogOk = reloadOne(source, "shop catalogue", ShopCatalogManager::reload);

        if (!(configOk && policyOk && catalogOk)) return 0;
        source.sendSuccess(() -> Component.literal("Reloaded the CobbleRaids config, reward policy and shop"
                + " catalogue from " + CobbleRaidsConfigManager.path().getParent()).withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static boolean reloadOne(CommandSourceStack source, String label, Runnable reload) {
        try {
            reload.run();
            return true;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to reload the " + label + ": " + ex.getMessage()));
            return false;
        }
    }

    /** Prints every effective setting, for the operator who wants to know what the server is actually using. */
    static int show(CommandSourceStack source) {
        CobbleRaidsConfig config = CobbleRaidsConfigManager.get();
        CobbleRaidsConfig.NaturalSpawning ns = config.naturalSpawning();

        source.sendSuccess(() -> CommandFormat.header("CobbleRaids config"), false);
        source.sendSuccess(() -> CommandFormat.hint(" " + CobbleRaidsConfigManager.path()), false);

        section(source, "natural_spawning");
        setting(source, "enabled", ns.enabled());
        setting(source, "check_interval_ticks", ns.checkIntervalTicks());
        setting(source, "spawn_attempt_chance", ns.spawnAttemptChance());
        setting(source, "attempts_per_check", ns.attemptsPerCheck());
        setting(source, "max_active_raids", ns.maxActiveRaids());
        setting(source, "max_active_raids_per_dimension", ns.maxActiveRaidsPerDimension());
        setting(source, "min_distance_between_raids", ns.minDistanceBetweenRaids());
        setting(source, "min_distance_from_player", ns.minDistanceFromPlayer());
        setting(source, "max_distance_from_player", ns.maxDistanceFromPlayer());
        setting(source, "location_attempts", ns.locationAttempts());
        setting(source, "despawn_player_radius", ns.despawnPlayerRadius());
        setting(source, "default_despawn_seconds", ns.defaultDespawnSeconds());
        setting(source, "default_max_lifetime_seconds", ns.defaultMaxLifetimeSeconds());
        setting(source, "default_definition_cooldown_seconds", ns.defaultDefinitionCooldownSeconds());
        // Printed together and in that order because they are the pair people confuse: weights pick
        // WHICH tier a spawn is, chances decide WHETHER it happens at all. Tuning the first to get
        // fewer common raids just hands those spawns to the rarer tiers.
        setting(source, "tier_weights", ns.tierWeights().starter() + "/" + ns.tierWeights().powerhouse()
                + "/" + ns.tierWeights().legendary() + "/" + ns.tierWeights().mythical() + " (mix, not rate)");
        setting(source, "tier_spawn_chance", ns.tierSpawnChance().starter() + "/" + ns.tierSpawnChance().powerhouse()
                + "/" + ns.tierSpawnChance().legendary() + "/" + ns.tierSpawnChance().mythical() + " (rate)");
        setting(source, "announcement_precision", ns.announcementPrecision().serializedName());

        section(source, "recruitment_defaults");
        setting(source, "duration_seconds", config.recruitmentDefaults().durationSeconds());
        setting(source, "radius", config.recruitmentDefaults().radius());
        setting(source, "max_players", config.recruitmentDefaults().maxPlayers());

        section(source, "combat_defaults");
        setting(source, "time_limit_seconds", config.combatDefaults().timeLimitSeconds());
        setting(source, "allow_flee", config.combatDefaults().allowFlee());
        setting(source, "max_failed_attempts", config.combatDefaults().maxFailedAttempts());
        setting(source, "carry_over_health", config.battleCarryover().health());
        setting(source, "carry_over_pp", config.battleCarryover().pp());
        setting(source, "carry_over_status", config.battleCarryover().status());

        section(source, "boss_glow");
        setting(source, "enabled", config.bossGlow().enabled());
        setting(source, "radius_blocks", config.bossGlow().radiusBlocks());

        section(source, "boss_movement");
        setting(source, "slowness_enabled", config.bossMovement().slownessEnabled());
        setting(source, "slowness_amplifier", config.bossMovement().slownessAmplifier()
                + " (Slowness " + (config.bossMovement().slownessAmplifier() + 1) + ")");
        setting(source, "prevent_knockback", config.bossMovement().preventKnockback());

        section(source, "boss_traits");
        setting(source, "iv_jitter", config.bossTraits().ivJitter());
        setting(source, "shiny_chance", config.bossTraits().shinyChance());

        section(source, "catching");
        setting(source, "enabled", config.catching().enabled());
        setting(source, "base_chance", tierValues(
                config.catching().chanceFor(RaidRarityTier.STARTER),
                config.catching().chanceFor(RaidRarityTier.POWERHOUSE),
                config.catching().chanceFor(RaidRarityTier.LEGENDARY),
                config.catching().chanceFor(RaidRarityTier.MYTHICAL)));
        setting(source, "ceiling_chance", tierValues(
                config.catching().ceilingFor(RaidRarityTier.STARTER),
                config.catching().ceilingFor(RaidRarityTier.POWERHOUSE),
                config.catching().ceilingFor(RaidRarityTier.LEGENDARY),
                config.catching().ceilingFor(RaidRarityTier.MYTHICAL)));

        section(source, "currency");
        setting(source, "enabled", config.currency().enabled());
        setting(source, "amount", tierValues(config.currency().starter(), config.currency().powerhouse(),
                config.currency().legendary(), config.currency().mythical()));
        setting(source, "scale_with_contribution", config.currency().scaleWithContribution());
        setting(source, "minimum_share_percentage", config.currency().minimumSharePercentage());

        section(source, "dynamic_level");
        setting(source, "enabled", config.dynamicLevel().enabled());
        setting(source, "level_offset", config.dynamicLevel().levelOffset());
        setting(source, "max_level", config.dynamicLevel().maxLevel());

        section(source, "mega_pity");
        setting(source, "enabled", config.megaPity().enabled());
        setting(source, "threshold", config.megaPity().threshold());

        section(source, "raid_points");
        setting(source, "enabled", config.raidPoints().enabled());
        setting(source, "amount", tierValues(config.raidPoints().starter(), config.raidPoints().powerhouse(),
                config.raidPoints().legendary(), config.raidPoints().mythical()));

        section(source, "personal_boss_shop");
        setting(source, "enabled", config.personalBossShop().enabled());
        setting(source, "reroll_cost", config.personalBossShop().rerollCost());
        setting(source, "buy_cost", tierValues(config.personalBossShop().buyCostStarter(),
                config.personalBossShop().buyCostPowerhouse(), config.personalBossShop().buyCostLegendary(),
                config.personalBossShop().buyCostMythical()));

        section(source, "tier_scaling");
        setting(source, "enabled", config.tierScaling().enabled());
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            CobbleRaidsConfig.TierMultipliers multipliers = config.tierScaling().forTier(tier);
            setting(source, tier.serializedName(), "health x" + multipliers.health()
                    + ", time x" + multipliers.timeLimit() + ", reward x" + multipliers.reward());
        }

        section(source, "renown");
        setting(source, "enabled", config.renown().enabled());
        setting(source, "chance", tierValues(config.renown().starter(), config.renown().powerhouse(),
                config.renown().legendary(), config.renown().mythical()));
        setting(source, "health_bonus", config.renown().healthBonus());
        setting(source, "stat_focus_evs", config.renown().statFocusEvs());
        setting(source, "level_bonus", config.renown().levelBonus());
        setting(source, "base_health_bonus", config.renown().baseHealthBonus());
        setting(source, "points_multiplier", config.renown().pointsMultiplier());
        setting(source, "currency_multiplier", config.renown().currencyMultiplier());

        section(source, "reconnect_grace");
        setting(source, "enabled", config.reconnectGrace().enabled());
        setting(source, "grace_seconds", config.reconnectGrace().graceSeconds());

        section(source, "other");
        setting(source, "debug_logging", config.debugLogging());
        return 1;
    }

    /** Starter/powerhouse/legendary/mythical, in the one order every per-tier setting is printed. */
    private static String tierValues(Object starter, Object powerhouse, Object legendary, Object mythical) {
        return starter + "/" + powerhouse + "/" + legendary + "/" + mythical;
    }

    private static void section(CommandSourceStack source, String name) {
        source.sendSuccess(() -> Component.literal(name).withStyle(ChatFormatting.AQUA), false);
    }

    private static void setting(CommandSourceStack source, String key, Object value) {
        source.sendSuccess(() -> Component.literal(" " + CommandFormat.pad(key, 36))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE)), false);
    }
}
