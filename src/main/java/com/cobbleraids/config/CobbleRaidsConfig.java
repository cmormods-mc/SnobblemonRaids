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
        DynamicLevel dynamicLevel,
        MegaPity megaPity,
        RaidPoints raidPoints,
        PersonalBossShop personalBossShop,
        TierScaling tierScaling,
        BossGlow bossGlow,
        BossMovement bossMovement,
        Renown renown,
        ReconnectGrace reconnectGrace,
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
        /**
         * Off. Raid Points replaced this as what a raid pays; the figures are kept so a server that
         * would rather pay CobbleDollars only has to flip the switch. See RaidPointsStore for why
         * a mod-owned currency was preferred.
         */
        public static Currency defaults() { return new Currency(false, 2_000L, 5_000L, 12_000L, 25_000L, true, 10.0); }

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

    /**
     * Raises a boss to meet the group that recruited it, when they outclass its definition.
     *
     * <p>Upward only. A definition's level is a floor and never a ceiling, because loot is decided
     * by rarity tier rather than by level -- a boss that could scale down would let a low-level
     * group farm a mythical's Mega Stone odds off a trivial fight. See RaidLevelPolicy.
     *
     * <p>{@code level_offset} shifts the target off the party average: negative makes a raid a
     * step below the group's own level, positive a step above. It cannot pull a boss below its
     * definition either way.
     */
    public record DynamicLevel(boolean enabled, int levelOffset, int maxLevel) {
        public DynamicLevel {
            if (levelOffset < -50 || levelOffset > 50)
                throw new IllegalArgumentException("dynamic_level.level_offset must be -50..50");
            if (maxLevel < 1 || maxLevel > 100)
                throw new IllegalArgumentException("dynamic_level.max_level must be 1..100");
        }

        public static DynamicLevel defaults() { return new DynamicLevel(true, 0, 100); }
    }

    /**
     * Bad-luck protection on Mega Stones: guaranteed by the Nth mega-capable raid.
     *
     * <p>Counted in raids that could have dropped one, not in raids. It is what lets the drop rate
     * itself stay low -- 10% with a floor under it averages the same as 15% without one, and the
     * distribution is far tighter. See RaidMegaPity.
     */
    public record MegaPity(boolean enabled, int threshold) {
        public MegaPity {
            if (threshold < 1 || threshold > 200)
                throw new IllegalArgumentException("mega_pity.threshold must be 1..200");
        }

        public static MegaPity defaults() { return new MegaPity(true, 12); }
    }

    /**
     * Raid Points paid for a won raid, by tier.
     *
     * <p>A currency this mod owns, spent in the raid shop and earnable nowhere else, which is what
     * makes it a lever: CobbleDollars buys everything on a server, so what a raid was worth in them
     * depended on every other mod's prices. Points depend on nothing.
     *
     * <p>Flat per claim rather than split by damage share. Every eligible participant fought the
     * same raid, and a shop currency whose price list is fixed reads badly when what you earn
     * toward it is not -- the contribution reward is the extra loot selections, which already
     * scale.
     */
    public record RaidPoints(boolean enabled, int starter, int powerhouse, int legendary, int mythical) {
        private static final int MAX_AWARD = 100_000;

        public RaidPoints {
            validate("starter", starter);
            validate("powerhouse", powerhouse);
            validate("legendary", legendary);
            validate("mythical", mythical);
        }

        public static RaidPoints defaults() { return new RaidPoints(true, 25, 50, 75, 100); }

        public int pointsFor(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        /** True when no tier pays anything, so the claim path can skip the award entirely. */
        public boolean isNoOp() {
            return starter <= 0 && powerhouse <= 0 && legendary <= 0 && mythical <= 0;
        }

        private static void validate(String name, int amount) {
            if (amount < 0 || amount > MAX_AWARD)
                throw new IllegalArgumentException("raid_points." + name + " must be 0.." + MAX_AWARD);
        }
    }

    /**
     * Buying back the exact boss a player helped defeat, and the risky IV/EV reroll gamble on that
     * saved snapshot before buying it.
     *
     * <p>Buy-back cost is priced per rarity tier, the same shape as {@link RaidPoints}, because what
     * a boss is worth to buy back should track what winning it was worth in the first place.
     * Reroll cost is flat: it is a gamble on a snapshot already captured, not a reward tied to a
     * raid's difficulty. The reroll itself has no configurable spread -- see
     * {@link RaidBossTraits#rollIv}/{@link RaidBossTraits#rollEvs}: every stat is a fresh, uniform
     * roll across its whole legal range, with no relationship to what it was a moment ago. That is
     * the point of a gamble; a small nudge away from the current value would not be one.
     *
     * <p>Independent of {@link Catching}: that mechanic is an instant, free, config-gated chance
     * roll at the moment of victory, off by default. This is a guaranteed-but-paid, delayed second
     * path to the same underlying idea, on by default -- the two coexist and neither implies the
     * other's setting.
     */
    public record PersonalBossShop(boolean enabled, int rerollCost,
                                   int buyCostStarter, int buyCostPowerhouse,
                                   int buyCostLegendary, int buyCostMythical) {
        private static final int MAX_COST = 100_000;

        public PersonalBossShop {
            if (rerollCost < 0 || rerollCost > MAX_COST)
                throw new IllegalArgumentException("personal_boss_shop.reroll_cost must be 0.." + MAX_COST);
            validateCost("buy_cost_starter", buyCostStarter);
            validateCost("buy_cost_powerhouse", buyCostPowerhouse);
            validateCost("buy_cost_legendary", buyCostLegendary);
            validateCost("buy_cost_mythical", buyCostMythical);
        }

        public static PersonalBossShop defaults() {
            return new PersonalBossShop(true, 50, 40, 80, 160, 300);
        }

        public int buyCostFor(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> buyCostStarter;
                case POWERHOUSE -> buyCostPowerhouse;
                case LEGENDARY -> buyCostLegendary;
                case MYTHICAL -> buyCostMythical;
            };
        }

        private static void validateCost(String name, int amount) {
            if (amount < 0 || amount > MAX_COST)
                throw new IllegalArgumentException("personal_boss_shop." + name + " must be 0.." + MAX_COST);
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

    /**
     * Renowned bosses: a per-tier chance for a spawn to carry a title such as "Kaelen, the
     * Relentless", a moderate boon from its epithet, and a better payout for beating it.
     *
     * <p>Every boon's strength is here and nowhere else. A datapack epithet only picks which kind
     * of boon it grants (see RenownBoon), so the operator decides how much harder renown is, and
     * retunes it in one place:
     * <ul>
     *   <li>{@code health_bonus} -- share added to the raid health pool, after player count and level;
     *       capped at +50%.</li>
     *   <li>{@code stat_focus_evs} -- EVs added to the focused stat, alongside a maximum IV; never
     *       past Cobblemon's 252 per stat or 510 total.</li>
     * </ul>
     *
     * <p>The payout multipliers apply to Raid Points and currency. Contribution bonus rolls are
     * deliberately left alone: renown pays in the shop currency, not in extra loot selections. Both
     * floor, and neither can go below 1 -- renown never pays less than an ordinary boss.
     *
     * <p>The shipped strengths are a starting point that has not been balanced in live play yet.
     */
    public record Renown(boolean enabled, double starter, double powerhouse, double legendary, double mythical,
                         double healthBonus, int statFocusEvs, double pointsMultiplier, double currencyMultiplier) {
        public Renown {
            validateChance("starter", starter);
            validateChance("powerhouse", powerhouse);
            validateChance("legendary", legendary);
            validateChance("mythical", mythical);
            if (!(healthBonus >= 0.0) || healthBonus > 0.5)
                throw new IllegalArgumentException("renown.health_bonus must be 0..0.5");
            if (statFocusEvs < 0 || statFocusEvs > 252)
                throw new IllegalArgumentException("renown.stat_focus_evs must be 0..252");
            validateMultiplier("points_multiplier", pointsMultiplier);
            validateMultiplier("currency_multiplier", currencyMultiplier);
        }

        public static Renown defaults() { return new Renown(true, 0.05, 0.07, 0.10, 0.12, 0.15, 128, 1.25, 1.25); }

        public double chanceFor(RaidRarityTier tier) {
            return switch (tier) {
                case STARTER -> starter;
                case POWERHOUSE -> powerhouse;
                case LEGENDARY -> legendary;
                case MYTHICAL -> mythical;
            };
        }

        private static void validateChance(String name, double chance) {
            if (!(chance >= 0.0) || chance > 1.0)
                throw new IllegalArgumentException("renown.chance." + name + " must be 0..1");
        }

        private static void validateMultiplier(String name, double multiplier) {
            if (!(multiplier >= 1.0) || multiplier > 3.0)
                throw new IllegalArgumentException("renown." + name + " must be 1..3");
        }
    }

    /**
     * How long a disconnected raider's slot is held open before their departure is made permanent.
     *
     * <p>Without this, {@code RaidLifecycleCoordinator.onPlayerDisconnected} forfeits a raider's
     * rewards and drops them from the raid the instant Cobblemon reports the disconnect -- a dropped
     * connection costs exactly as much as deliberately quitting. {@link RaidReconnectService} holds
     * their spot for {@code graceSeconds} instead, and only falls through to that same forfeiting
     * path once the grace period actually lapses without them coming back. See
     * {@code com.cobbleraids.lifecycle.RaidReconnectService}.
     */
    public record ReconnectGrace(boolean enabled, int graceSeconds) {
        public ReconnectGrace {
            if (graceSeconds < 1 || graceSeconds > 3600)
                throw new IllegalArgumentException("reconnect_grace.grace_seconds must be 1..3600");
        }

        public static ReconnectGrace defaults() { return new ReconnectGrace(true, 300); }
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
                        // One check a minute, 5% of which proceed: a raid every 20 minutes on
                        // average, three an hour. Set alongside the Mega Stone rate rather than on
                        // its own -- supply is the dominant term in how often a stone turns up, and
                        // at 45 minutes no sane drop rate reached a four-hour stone.
                        //
                        // The distribution is geometric rather than a fixed timer: the wait is
                        // memoryless, so raids do not become predictable and two can fall close
                        // together. 20 minutes is the mean, not the interval.
                        0.05,           // spawn_attempt_chance
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
                DynamicLevel.defaults(),
                MegaPity.defaults(),
                RaidPoints.defaults(),
                PersonalBossShop.defaults(),
                TierScaling.defaults(),
                BossGlow.defaults(),
                BossMovement.defaults(),
                Renown.defaults(),
                ReconnectGrace.defaults(),
                false
        );
    }

    /** JSON reading/writing lives in {@link CobbleRaidsConfigCodec}; this type stays schema-only. */
    public static CobbleRaidsConfig fromJson(JsonObject root) {
        return CobbleRaidsConfigCodec.fromJson(root);
    }

    public JsonObject toJson() {
        return CobbleRaidsConfigCodec.toJson(this);
    }
}
