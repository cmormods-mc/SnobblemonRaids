package com.cobbleraids.config;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonObject;
import java.util.Locale;

/** Server-owner tuning values. Raid datapack JSON can override content-specific values. */
public record CobbleRaidsConfig(
        NaturalSpawning naturalSpawning,
        RecruitmentDefaults recruitmentDefaults,
        CombatDefaults combatDefaults,
        BattleCarryover battleCarryover,
        BossTraits bossTraits,
        Catching catching,
        Currency currency,
        TierScaling tierScaling,
        BossGlow bossGlow,
        BossMovement bossMovement,
        boolean debugLogging
) {
    public static final int VALIDATED_MAX_HUMAN_PLAYERS = 4;

    /** How much location detail a natural-spawn announcement reveals to the whole server. */
    public enum AnnouncementPrecision {
        EXACT, NEAREST_HUNDRED, BIOME_ONLY, DISABLED;

        public String serializedName() { return name().toLowerCase(Locale.ROOT); }

        public static AnnouncementPrecision parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record NaturalSpawning(
            boolean enabled,
            int checkIntervalTicks,
            double spawnAttemptChance,
            int attemptsPerCheck,
            int maxActiveRaids,
            int maxActiveRaidsPerDimension,
            double minDistanceFromPlayer,
            double maxDistanceFromPlayer,
            double minDistanceBetweenRaids,
            int locationAttempts,
            double despawnPlayerRadius,
            int defaultDespawnSeconds,
            int defaultMaxLifetimeSeconds,
            int defaultDefinitionCooldownSeconds,
            RaidTierWeights tierWeights,
            RaidTierSpawnChance tierSpawnChance,
            AnnouncementPrecision announcementPrecision
    ) {
        public NaturalSpawning {
            if (checkIntervalTicks < 20 || checkIntervalTicks > 72_000)
                throw new IllegalArgumentException("natural_spawning.check_interval_ticks must be 20..72000");
            if (spawnAttemptChance < 0.0 || spawnAttemptChance > 1.0)
                throw new IllegalArgumentException("natural_spawning.spawn_attempt_chance must be 0..1");
            if (attemptsPerCheck < 1 || attemptsPerCheck > 64)
                throw new IllegalArgumentException("natural_spawning.attempts_per_check must be 1..64");
            if (maxActiveRaids < 1 || maxActiveRaids > 256)
                throw new IllegalArgumentException("natural_spawning.max_active_raids must be 1..256");
            if (maxActiveRaidsPerDimension < 1 || maxActiveRaidsPerDimension > maxActiveRaids)
                throw new IllegalArgumentException("natural_spawning.max_active_raids_per_dimension must be 1..max_active_raids");
            if (!(minDistanceFromPlayer >= 8.0) || !(maxDistanceFromPlayer > minDistanceFromPlayer) || maxDistanceFromPlayer > 512.0)
                throw new IllegalArgumentException("natural spawn player distances must satisfy 8 <= min < max <= 512");
            if (minDistanceBetweenRaids < 0.0 || minDistanceBetweenRaids > 4096.0)
                throw new IllegalArgumentException("natural_spawning.min_distance_between_raids must be 0..4096");
            if (locationAttempts < 1 || locationAttempts > 128)
                throw new IllegalArgumentException("natural_spawning.location_attempts must be 1..128");
            if (despawnPlayerRadius < 1.0 || despawnPlayerRadius > 512.0)
                throw new IllegalArgumentException("natural_spawning.despawn_player_radius must be 1..512");
            // Not fatal, because existing server.json files ship the old equal values and an
            // upgrade must not refuse to boot. Wild raids are placed between min_distance_from_player
            // and max_distance_from_player, so a despawn radius at or above that ceiling means every
            // boss is born inside its own keep-alive bubble and the unattended timer never starts.
            if (despawnPlayerRadius >= maxDistanceFromPlayer)
                RaidLog.warn("natural_spawning.despawn_player_radius ("
                        + despawnPlayerRadius + ") is not smaller than natural_spawning.max_distance_from_player ("
                        + maxDistanceFromPlayer + "), so wild raid bosses spawn already inside the radius that"
                        + " keeps them alive. Unattended bosses will not despawn until a player leaves that radius.");
            if (defaultDespawnSeconds < 1 || defaultDespawnSeconds > 86_400)
                throw new IllegalArgumentException("natural_spawning.default_despawn_seconds must be 1..86400");
            // Deliberately has no "unlimited" value. despawn_seconds only measures time with nobody
            // nearby, so a player standing next to a boss resets it forever -- this cap is the only
            // thing bounding a held boss, and an off switch would silently restore that. Set it to
            // 86400 if a 24h ceiling is genuinely wanted.
            if (defaultMaxLifetimeSeconds < 60 || defaultMaxLifetimeSeconds > 86_400)
                throw new IllegalArgumentException("natural_spawning.default_max_lifetime_seconds must be 60..86400");
            if (defaultMaxLifetimeSeconds < defaultDespawnSeconds)
                RaidLog.warn("natural_spawning.default_max_lifetime_seconds ("
                        + defaultMaxLifetimeSeconds + ") is below default_despawn_seconds (" + defaultDespawnSeconds
                        + "), so the total lifetime cap will usually fire before the unattended timer ever can.");
            if (defaultDefinitionCooldownSeconds < 0 || defaultDefinitionCooldownSeconds > 604_800)
                throw new IllegalArgumentException("natural_spawning.default_definition_cooldown_seconds must be 0..604800");
            if (tierWeights == null)
                throw new IllegalArgumentException("natural_spawning.tier_weights cannot be null");
            if (tierSpawnChance == null)
                throw new IllegalArgumentException("natural_spawning.tier_spawn_chance cannot be null");
            if (announcementPrecision == null)
                throw new IllegalArgumentException("natural_spawning.announcement_precision cannot be null");
        }
    }

    public record RecruitmentDefaults(int durationSeconds, double radius, int maxPlayers) {
        public RecruitmentDefaults {
            if (durationSeconds < 1 || durationSeconds > 600)
                throw new IllegalArgumentException("recruitment_defaults.duration_seconds must be 1..600");
            if (!(radius > 0.0) || radius > 128.0)
                throw new IllegalArgumentException("recruitment_defaults.radius must be > 0 and <= 128");
            if (maxPlayers < 1 || maxPlayers > VALIDATED_MAX_HUMAN_PLAYERS)
                throw new IllegalArgumentException("recruitment_defaults.max_players must be 1.." + VALIDATED_MAX_HUMAN_PLAYERS);
        }
    }


    /** Defaults applied when an individual raid JSON omits combat rule fields. */
    public record CombatDefaults(int timeLimitSeconds, boolean allowFlee, int maxFailedAttempts) {
        public CombatDefaults {
            if (timeLimitSeconds < 0 || timeLimitSeconds > 86_400)
                throw new IllegalArgumentException("combat_defaults.time_limit_seconds must be 0..86400");
            if (maxFailedAttempts < 0 || maxFailedAttempts > 1000)
                throw new IllegalArgumentException("combat_defaults.max_failed_attempts must be 0..1000");
        }

        /** 0 disables the cap, so a boss can be attempted until one of its timers removes it. */
        public boolean attemptsAreLimited() { return maxFailedAttempts > 0; }
    }

    /**
     * How much of a raid's battle damage follows the players home -- the cost of a raid.
     *
     * <p>Raids are fought on clones ({@code toBattleTeam(clone = true)}), so nothing that happens in
     * one reaches the party unless it is copied across deliberately. Health and PP are copied by
     * default so a raid costs something legible: you come out of it needing to heal. Faints come
     * with health for free, because {@code Pokemon.isFainted()} is just {@code currentHealth <= 0},
     * and Cobblemon's own faint timer then revives them on its usual schedule -- the cost is
     * self-limiting without any extra machinery here.
     *
     * <p>Status is off by default on purpose. A burn or paralysis that outlives the raid lands by
     * luck rather than by play, and is the part players push back on hardest. Turn it on for a
     * harsher server.
     */
    /**
     * Server-wide handling of the per-definition "traits" block.
     *
     * <p>ivJitter spreads a <em>pinned</em> IV by up to this much in either direction so repeat
     * raids are not byte-identical; 0 pins exactly. It has no effect on a definition that leaves
     * IVs unset, which stays on Cobblemon's fully random roll.
     *
     * <p>shinyChance is rolled per spawn. Bosses are uncatchable today, so a shiny one is currently
     * a spectacle rather than a prize.
     */
    public record BossTraits(int ivJitter, double shinyChance) {
        public BossTraits {
            if (ivJitter < 0 || ivJitter > 31)
                throw new IllegalArgumentException("boss_traits.iv_jitter must be 0..31");
            if (!(shinyChance >= 0.0) || shinyChance > 1.0)
                throw new IllegalArgumentException("boss_traits.shiny_chance must be 0..1");
        }

        public static BossTraits defaults() { return new BossTraits(3, 0.01); }
    }

    /**
     * Whether a victor may keep the boss, and how likely that is per rarity tier.
     *
     * <p>Off, and zero everywhere, by default. The catching infrastructure is deliberately inert
     * until a mechanic is chosen: these flat per-tier odds exist so the wiring can be proven end to
     * end, not as a proposal for how catching should finally work. See RaidCatchPolicy.
     */
    public record Catching(boolean enabled, double starter, double powerhouse,
                           double legendary, double mythical) {
        public Catching {
            validate("starter", starter);
            validate("powerhouse", powerhouse);
            validate("legendary", legendary);
            validate("mythical", mythical);
        }

        public static Catching defaults() { return new Catching(false, 0.0, 0.0, 0.0, 0.0); }

        public double chanceFor(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        /** True when no tier can ever be caught, so the victory path can skip the roll entirely. */
        public boolean isNoOp() {
            return starter <= 0.0 && powerhouse <= 0.0 && legendary <= 0.0 && mythical <= 0.0;
        }

        private static void validate(String name, double chance) {
            if (!(chance >= 0.0) || chance > 1.0)
                throw new IllegalArgumentException("catching." + name + " must be 0..1");
        }
    }

    /**
     * Currency paid alongside a claimed raid reward, when a supported economy mod is installed.
     *
     * <p>The shipped figures are priced against CobbleDollars' own default shop, where a Poke Ball
     * costs 2000, a Great Ball 6000, an Ultra Ball 8000 and a Rare Candy 100000. A server that has
     * rewritten those prices should rewrite these too.
     *
     * <p>They are a top-up, not the whole payout. CobbleDollars already pays every winner of a
     * battle whose loser is a WILD actor, which a raid boss is, on a curve of roughly
     * {@code level squared / 10} with a random 1.5x-3x spread -- about 1264 for a level 75 starter
     * boss and 2250 for a level 100 legendary, to each participant, flat. That curve cannot tell a
     * legendary from a mythical, because both are level 100; this block is what makes the tiers
     * differ, and what makes a carry worth more than a tag-along.
     *
     * <p>{@code scale_with_contribution} chooses between paying every eligible victor the tier's
     * full amount and splitting it by damage share; {@code minimum_share_percentage} withholds the
     * payout entirely below a threshold, so a player who tagged the boss once does not get paid.
     * See RaidCurrencyPolicy for the arithmetic, and RaidCurrencyBackends for who moves the money.
     */
    public record Currency(boolean enabled, long starter, long powerhouse, long legendary, long mythical,
                           boolean scaleWithContribution, double minimumSharePercentage) {
        /** Well beyond any sane payout, and far enough from overflow that scaling cannot wrap. */
        private static final long MAX_AMOUNT = 1_000_000_000L;

        public Currency {
            validate("starter", starter);
            validate("powerhouse", powerhouse);
            validate("legendary", legendary);
            validate("mythical", mythical);
            if (!(minimumSharePercentage >= 0.0) || minimumSharePercentage > 100.0)
                throw new IllegalArgumentException("currency.minimum_share_percentage must be 0..100");
        }

        /**
         * Roughly: a starter raid buys a Poke Ball, a powerhouse most of a Great Ball, a legendary
         * an Ultra Ball and a half, a mythical about three. Split by share, so a solo victor takes
         * the whole amount and a four-way raid divides it, and withheld entirely below a tenth of
         * the damage so that tagging a boss once pays nothing.
         */
        public static Currency defaults() { return new Currency(true, 2_000L, 5_000L, 12_000L, 25_000L, true, 10.0); }

        /** The all-zero configuration, kept for tests and for an operator switching payouts off. */
        public static Currency disabled() { return new Currency(false, 0L, 0L, 0L, 0L, false, 0.0); }

        public long amountFor(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        /** True when no tier can ever pay out, so the claim path can skip the backend entirely. */
        public boolean isNoOp() {
            return starter <= 0L && powerhouse <= 0L && legendary <= 0L && mythical <= 0L;
        }

        private static void validate(String name, long amount) {
            if (amount < 0L || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("currency." + name + " must be 0.." + MAX_AMOUNT);
        }
    }

    public record BattleCarryover(boolean health, boolean pp, boolean status) {
        public static BattleCarryover defaults() { return new BattleCarryover(true, true, false); }

        /** True when nothing at all is carried, so the whole pass can be skipped. */
        public boolean isNoOp() { return !health && !pp && !status; }
    }

    /**
     * Per-tier multipliers layered on top of each raid definition's own hand-tuned values.
     * Disabled by default: the 130 shipped definitions already carry tier-appropriate health and
     * rewards (a legendary's base_health is already higher than a starter's), so turning this on
     * compounds with that existing tuning rather than replacing it -- read the numbers in your
     * definitions before enabling, and adjust the multipliers here to match.
     */
    public record TierScaling(
            boolean enabled,
            TierMultipliers starter,
            TierMultipliers powerhouse,
            TierMultipliers legendary,
            TierMultipliers mythical
    ) {
        public TierScaling {
            if (starter == null || powerhouse == null || legendary == null || mythical == null)
                throw new IllegalArgumentException("tier_scaling multipliers cannot be null");
        }

        public TierMultipliers forTier(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        public static TierScaling defaults() {
            return new TierScaling(
                    false,
                    new TierMultipliers(1.0, 1.0, 1.0),
                    new TierMultipliers(1.15, 1.1, 1.15),
                    new TierMultipliers(1.35, 1.25, 1.35),
                    new TierMultipliers(1.5, 1.4, 1.5)
            );
        }
    }

    /**
     * Keeps a raid boss where it spawned and stops anyone shoving it around.
     *
     * <p>Both halves are about the same failure: a boss that has wandered off is one nobody at the
     * announced coordinates can find, and one that can be launched is an event any player can ruin
     * on their own. Slowness rather than NoAI so the boss still turns, looks at players and
     * animates -- a frozen statue reads as broken.
     */
    public record BossMovement(boolean slownessEnabled, int slownessAmplifier, boolean preventKnockback) {
        public BossMovement {
            // Slowness reduces speed by 15% per level, and level is amplifier + 1, so amplifier 6
            // (Slowness VII) is the first that fully roots the boss. Lower values let it drift.
            if (slownessAmplifier < 0 || slownessAmplifier > 9)
                throw new IllegalArgumentException("boss_movement.slowness_amplifier must be 0..9");
        }

        public static BossMovement defaults() {
            return new BossMovement(true, 6, true);
        }
    }

    /** Lets players spot a raid boss through terrain from a distance, tinted per rarity tier. */
    public record BossGlow(boolean enabled, double radiusBlocks) {
        public BossGlow {
            if (radiusBlocks < 1.0 || radiusBlocks > 512.0)
                throw new IllegalArgumentException("boss_glow.radius_blocks must be 1..512");
        }

        public static BossGlow defaults() {
            return new BossGlow(true, 48.0);
        }
    }

    public record TierMultipliers(double health, double timeLimit, double reward) {
        public TierMultipliers {
            if (health < 0.1 || health > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier health must be 0.1..10");
            if (timeLimit < 0.1 || timeLimit > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier time_limit must be 0.1..10");
            if (reward < 0.1 || reward > 10.0)
                throw new IllegalArgumentException("tier_scaling multiplier reward must be 0.1..10");
        }
    }

    public static CobbleRaidsConfig defaults() {
        return new CobbleRaidsConfig(
                new NaturalSpawning(
                        true,           // enabled
                        1200,           // check_interval_ticks
                        // One check a minute, 2.22% of which proceed, so a raid every 45 minutes
                        // on average. It was 0.25 -- one every four minutes, about fifteen an hour,
                        // each announced server-wide -- which a live server reported as constant.
                        //
                        // The distribution is geometric rather than a fixed timer: the wait is
                        // memoryless, so raids do not become predictable and two can fall close
                        // together. 45 minutes is the mean, not the interval.
                        0.0222,         // spawn_attempt_chance
                        1,              // attempts_per_check
                        // Left at 10/4. These were raised from 3/2 because at three global slots a
                        // handful of players sitting on bosses could stop wild raids for everyone,
                        // each held boss also blocking a 128-block radius. At one spawn per 45
                        // minutes the cap is a ceiling rather than the governor -- the rate decides
                        // supply now, and the cap is only there to stop a pile-up.
                        10,             // max_active_raids
                        4,              // max_active_raids_per_dimension
                        24.0,           // min_distance_from_player
                        64.0,           // max_distance_from_player
                        128.0,          // min_distance_between_raids
                        16,             // location_attempts
                        32.0,           // despawn_player_radius
                        600,            // default_despawn_seconds (unattended only)
                        1800,           // default_max_lifetime_seconds (total, camped or not)
                        1800,           // default_definition_cooldown_seconds
                        RaidTierWeights.defaults(),
                        RaidTierSpawnChance.defaults(),
                        AnnouncementPrecision.NEAREST_HUNDRED
                ),
                new RecruitmentDefaults(20, 10.0, VALIDATED_MAX_HUMAN_PLAYERS),
                new CombatDefaults(900, false, 3),
                BattleCarryover.defaults(),
                BossTraits.defaults(),
                Catching.defaults(),
                Currency.defaults(),
                TierScaling.defaults(),
                BossGlow.defaults(),
                BossMovement.defaults(),
                false
        );
    }

    public static CobbleRaidsConfig fromJson(JsonObject root) {
        CobbleRaidsConfig defaults = defaults();

        JsonObject natural = Json.object(root, "natural_spawning");
        NaturalSpawning nd = defaults.naturalSpawning();
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
        NaturalSpawning naturalSpawning = new NaturalSpawning(
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
                        ? AnnouncementPrecision.parse(natural.get("announcement_precision").getAsString())
                        : nd.announcementPrecision()
        );

        JsonObject recruitment = Json.object(root, "recruitment_defaults");
        RecruitmentDefaults rd = defaults.recruitmentDefaults();
        RecruitmentDefaults recruitmentDefaults = new RecruitmentDefaults(
                Json.integer(recruitment, "duration_seconds", rd.durationSeconds()),
                Json.decimal(recruitment, "radius", rd.radius()),
                Json.integer(recruitment, "max_players", rd.maxPlayers())
        );

        JsonObject combat = Json.object(root, "combat_defaults");
        CombatDefaults cd = defaults.combatDefaults();
        CombatDefaults combatDefaults = new CombatDefaults(
                Json.integer(combat, "time_limit_seconds", cd.timeLimitSeconds()),
                Json.bool(combat, "allow_flee", cd.allowFlee()),
                Json.integer(combat, "max_failed_attempts", cd.maxFailedAttempts())
        );

        JsonObject carryover = Json.object(root, "battle_carryover");
        BattleCarryover bc = defaults.battleCarryover();
        BattleCarryover battleCarryover = new BattleCarryover(
                Json.bool(carryover, "health", bc.health()),
                Json.bool(carryover, "pp", bc.pp()),
                Json.bool(carryover, "status", bc.status())
        );

        JsonObject bossTraitsObject = Json.object(root, "boss_traits");
        BossTraits bt = defaults.bossTraits();
        BossTraits bossTraits = new BossTraits(
                Json.integer(bossTraitsObject, "iv_jitter", bt.ivJitter()),
                Json.decimal(bossTraitsObject, "shiny_chance", bt.shinyChance())
        );

        JsonObject catchingObject = Json.object(root, "catching");
        Catching cat = defaults.catching();
        Catching catching = new Catching(
                Json.bool(catchingObject, "enabled", cat.enabled()),
                Json.decimal(catchingObject, "starter", cat.starter()),
                Json.decimal(catchingObject, "powerhouse", cat.powerhouse()),
                Json.decimal(catchingObject, "legendary", cat.legendary()),
                Json.decimal(catchingObject, "mythical", cat.mythical())
        );

        JsonObject currencyObject = Json.object(root, "currency");
        Currency cur = defaults.currency();
        Currency currency = new Currency(
                Json.bool(currencyObject, "enabled", cur.enabled()),
                Json.integer64(currencyObject, "starter", cur.starter()),
                Json.integer64(currencyObject, "powerhouse", cur.powerhouse()),
                Json.integer64(currencyObject, "legendary", cur.legendary()),
                Json.integer64(currencyObject, "mythical", cur.mythical()),
                Json.bool(currencyObject, "scale_with_contribution", cur.scaleWithContribution()),
                Json.decimal(currencyObject, "minimum_share_percentage", cur.minimumSharePercentage())
        );

        JsonObject tierScalingObject = Json.object(root, "tier_scaling");
        TierScaling ts = defaults.tierScaling();
        TierScaling tierScaling = new TierScaling(
                Json.bool(tierScalingObject, "enabled", ts.enabled()),
                readTierMultipliers(tierScalingObject, "starter", ts.starter()),
                readTierMultipliers(tierScalingObject, "powerhouse", ts.powerhouse()),
                readTierMultipliers(tierScalingObject, "legendary", ts.legendary()),
                readTierMultipliers(tierScalingObject, "mythical", ts.mythical())
        );

        JsonObject bossGlowObject = Json.object(root, "boss_glow");
        BossGlow bg = defaults.bossGlow();
        BossGlow bossGlow = new BossGlow(
                Json.bool(bossGlowObject, "enabled", bg.enabled()),
                Json.decimal(bossGlowObject, "radius_blocks", bg.radiusBlocks())
        );

        JsonObject bossMovementObject = Json.object(root, "boss_movement");
        BossMovement bm = defaults.bossMovement();
        BossMovement bossMovement = new BossMovement(
                Json.bool(bossMovementObject, "slowness_enabled", bm.slownessEnabled()),
                Json.integer(bossMovementObject, "slowness_amplifier", bm.slownessAmplifier()),
                Json.bool(bossMovementObject, "prevent_knockback", bm.preventKnockback()));

        return new CobbleRaidsConfig(naturalSpawning, recruitmentDefaults, combatDefaults, battleCarryover,
                bossTraits, catching, currency, tierScaling, bossGlow, bossMovement,
                Json.bool(root, "debug_logging", defaults.debugLogging()));
    }

    private static TierMultipliers readTierMultipliers(JsonObject tierScaling, String key, TierMultipliers fallback) {
        JsonObject tier = Json.object(tierScaling, key);
        return new TierMultipliers(
                Json.decimal(tier, "health_multiplier", fallback.health()),
                Json.decimal(tier, "time_limit_multiplier", fallback.timeLimit()),
                Json.decimal(tier, "reward_multiplier", fallback.reward())
        );
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
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

        JsonObject recruitment = new JsonObject();
        recruitment.addProperty("duration_seconds", recruitmentDefaults.durationSeconds());
        recruitment.addProperty("radius", recruitmentDefaults.radius());
        recruitment.addProperty("max_players", recruitmentDefaults.maxPlayers());
        root.add("recruitment_defaults", recruitment);

        JsonObject combat = new JsonObject();
        combat.addProperty("time_limit_seconds", combatDefaults.timeLimitSeconds());
        combat.addProperty("allow_flee", combatDefaults.allowFlee());
        combat.addProperty("max_failed_attempts", combatDefaults.maxFailedAttempts());
        root.add("combat_defaults", combat);

        JsonObject carryoverObject = new JsonObject();
        carryoverObject.addProperty("health", battleCarryover.health());
        carryoverObject.addProperty("pp", battleCarryover.pp());
        carryoverObject.addProperty("status", battleCarryover.status());
        root.add("battle_carryover", carryoverObject);

        JsonObject bossTraitsJson = new JsonObject();
        bossTraitsJson.addProperty("iv_jitter", bossTraits.ivJitter());
        bossTraitsJson.addProperty("shiny_chance", bossTraits.shinyChance());
        root.add("boss_traits", bossTraitsJson);

        JsonObject catchingJson = new JsonObject();
        catchingJson.addProperty("enabled", catching.enabled());
        catchingJson.addProperty("starter", catching.starter());
        catchingJson.addProperty("powerhouse", catching.powerhouse());
        catchingJson.addProperty("legendary", catching.legendary());
        catchingJson.addProperty("mythical", catching.mythical());
        root.add("catching", catchingJson);

        JsonObject currencyJson = new JsonObject();
        currencyJson.addProperty("enabled", currency.enabled());
        currencyJson.addProperty("starter", currency.starter());
        currencyJson.addProperty("powerhouse", currency.powerhouse());
        currencyJson.addProperty("legendary", currency.legendary());
        currencyJson.addProperty("mythical", currency.mythical());
        currencyJson.addProperty("scale_with_contribution", currency.scaleWithContribution());
        currencyJson.addProperty("minimum_share_percentage", currency.minimumSharePercentage());
        root.add("currency", currencyJson);

        JsonObject tierScalingObject = new JsonObject();
        tierScalingObject.addProperty("enabled", tierScaling.enabled());
        tierScalingObject.add("starter", tierMultipliersJson(tierScaling.starter()));
        tierScalingObject.add("powerhouse", tierMultipliersJson(tierScaling.powerhouse()));
        tierScalingObject.add("legendary", tierMultipliersJson(tierScaling.legendary()));
        tierScalingObject.add("mythical", tierMultipliersJson(tierScaling.mythical()));
        root.add("tier_scaling", tierScalingObject);

        JsonObject bossGlowObject = new JsonObject();
        bossGlowObject.addProperty("enabled", bossGlow.enabled());
        bossGlowObject.addProperty("radius_blocks", bossGlow.radiusBlocks());
        root.add("boss_glow", bossGlowObject);

        JsonObject bossMovementJson = new JsonObject();
        bossMovementJson.addProperty("slowness_enabled", bossMovement.slownessEnabled());
        bossMovementJson.addProperty("slowness_amplifier", bossMovement.slownessAmplifier());
        bossMovementJson.addProperty("prevent_knockback", bossMovement.preventKnockback());
        root.add("boss_movement", bossMovementJson);

        root.addProperty("debug_logging", debugLogging);
        return root;
    }

    private static JsonObject tierMultipliersJson(TierMultipliers multipliers) {
        JsonObject object = new JsonObject();
        object.addProperty("health_multiplier", multipliers.health());
        object.addProperty("time_limit_multiplier", multipliers.timeLimit());
        object.addProperty("reward_multiplier", multipliers.reward());
        return object;
    }

}
