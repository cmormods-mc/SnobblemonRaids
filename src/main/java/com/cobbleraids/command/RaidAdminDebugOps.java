package com.cobbleraids.command;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.fault.RaidAuditReport;
import com.cobbleraids.fault.RaidConsistencyAudit;
import com.cobbleraids.lobby.RaidLobby;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.RaidLootRoller;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnHistory;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class RaidAdminDebugOps {
    private RaidAdminDebugOps() {}

    /**
     * Runs the cross-subsystem consistency sweep now and prints what it found.
     *
     * <p>The same sweep runs on a five-minute schedule and logs itself; this exists for the moment an
     * operator has a suspicion and wants an answer immediately, and for the live smoke test, which
     * fails the build if a freshly booted server is already inconsistent.
     */
    static int audit(CommandSourceStack source) {
        RaidAuditReport report = RaidConsistencyAudit.run(source.getServer());
        RaidConsistencyAudit.log(report);

        source.sendSuccess(() -> CommandFormat.header("Consistency audit"), false);
        if (report.isClean()) {
            source.sendSuccess(() -> CommandFormat.row(report.summary()).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendSuccess(() -> CommandFormat.row(report.summary()).withStyle(ChatFormatting.RED), false);
        for (String line : report.lines()) {
            source.sendSuccess(() -> CommandFormat.row(line).withStyle(ChatFormatting.YELLOW), false);
        }
        // The result count is the violation total, so a command block or script can react to it.
        return report.violationCount();
    }

    static int status(CommandSourceStack source) {
        List<PokemonEntity> bosses = RaidAdminBossOps.allBosses(source.getServer());
        int definitions = RaidDefinitionRegistry.all().size();
        int lobbies = RaidLobbyManager.all().size();
        int battles = RaidRegistry.all().size();
        int natural = RaidSpawnScheduler.activeCount(source.getServer());
        String rewardGui = RewardGuiBackends.active().name();

        source.sendSuccess(() -> CommandFormat.header("CobbleRaids status"), false);
        source.sendSuccess(() -> CommandFormat.row(definitions + " definitions · " + bosses.size()
                + " bosses · " + natural + " tracked wild"), false);
        source.sendSuccess(() -> CommandFormat.row(lobbies + " lobbies · " + battles
                + " battles · reward gui " + rewardGui), false);
        return bosses.size() + battles + lobbies;
    }

    /**
     * Rolls a loot table against the running player and reports what it produced, granting nothing.
     * The point is to be able to check a table -- especially another mod's -- before wiring it into
     * a reward, since a raid reward is claimed once and there is no second look.
     */
    static int lootPreview(CommandSourceStack source, ResourceLocation tableId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<ItemStack> rolled = RaidLootRoller.roll(player, tableId, "debug loot");
        if (rolled.isEmpty()) {
            source.sendSuccess(() -> Component.literal("'" + tableId
                            + "' rolled nothing. If that is unexpected, check the server log: a table that does"
                            + " not exist, or wants context a raid cannot supply, is reported there.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header("Loot preview  " + CommandFormat.shortId(tableId)), false);
        for (ItemStack stack : rolled) {
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(String.valueOf(stack.getCount()), 5)
                    + stack.getHoverName().getString()), false);
        }
        source.sendSuccess(() -> CommandFormat.row(rolled.size() + " stack(s) this roll; run it again for another"), false);
        return rolled.size();
    }

    /** A player's raid history: what every catch mechanic gets to reason about. */
    static int record(CommandSourceStack source, ServerPlayer target) {
        RaidPlayerRecord record = RaidPlayerRecords.get(target.getUUID());
        source.sendSuccess(() -> CommandFormat.header("Raid record  " + target.getGameProfile().getName()), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("won", 10) + record.raidsWon()
                + " raid(s) · " + record.bossesCaught() + " boss(es) caught"), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("by tier", 10)
                + Arrays.stream(RaidRarityTier.values())
                        .map(tier -> tier.serializedName() + " " + record.winsIn(tier))
                        .collect(Collectors.joining(" · "))), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("avg share", 10)
                + String.format(Locale.ROOT, "%.1f%%", record.averageContribution())), false);
        if (record.defeatsBySpecies().isEmpty()) return record.raidsWon();
        String top = record.defeatsBySpecies().entrySet().stream()
                .sorted(Map.Entry.<ResourceLocation, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> CommandFormat.shortId(e.getKey()) + " x" + e.getValue())
                .collect(Collectors.joining(" · "));
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("most", 10) + top), false);
        return record.raidsWon();
    }

    static int raids(CommandSourceStack source) {
        List<PokemonEntity> bosses = RaidAdminBossOps.allBosses(source.getServer());
        if (bosses.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No live raid bosses are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        bosses.sort(Comparator.comparing(b -> RaidBossEntityMarker.definitionId(b)
                .map(ResourceLocation::toString).orElse("~unknown")));
        source.sendSuccess(() -> CommandFormat.header("Live raid bosses (" + bosses.size() + ")"), false);
        for (PokemonEntity boss : bosses) sendBoss(source, boss);
        return bosses.size();
    }

    private static void sendBoss(CommandSourceStack source, PokemonEntity boss) {
        String definition = RaidBossEntityMarker.definitionId(boss)
                .map(CommandFormat::shortId).orElse("unknown");
        RaidSession session = RaidAdminBossOps.sessionForBoss(boss);
        RaidLobby lobby = RaidLobbyManager.get(boss);
        String state = session != null ? "battle" : lobby != null ? "lobby" : "idle";
        // "wild" only when it is true -- natural=false on every admin-spawned row was pure noise.
        // Tracked wild bosses also show what is left of their lifetime cap, which is the only way to
        // see the cap working; admin-spawned bosses are untracked and have none, hence the -1 check.
        long secondsLeft = RaidSpawnScheduler.secondsUntilExpiry(boss.getUUID());
        String origin = !RaidBossEntityMarker.isNatural(boss) ? ""
                : secondsLeft < 0L ? "  wild"
                : "  wild " + CommandFormat.duration(secondsLeft) + " left";

        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(definition, 14)
                        + CommandFormat.pad(state, 7)
                        + CommandFormat.shortId(boss.level().dimension().location()) + " ")
                .append(CommandFormat.teleport(boss.level().dimension().location().toString(),
                        boss.getX(), boss.getY(), boss.getZ()))
                .append(Component.literal(origin)), false);

        if (lobby != null) {
            long remaining = Math.max(0L, lobby.closesAtTick() - boss.level().getGameTime());
            source.sendSuccess(() -> CommandFormat.detail(lobby.status().name().toLowerCase(Locale.ROOT)
                    + " · " + lobby.joinedCount() + "/" + lobby.definition().recruitment().maxPlayers()
                    + " players · " + CommandFormat.seconds(remaining / 20.0) + " left"), false);
        }
        if (session != null) sendSession(source, session);
    }

    private static void sendSession(CommandSourceStack source, RaidSession session) {
        String timer = session.isTimed()
                ? CommandFormat.seconds(session.getRemainingCombatTicks() / 20.0) + " left" : "no limit";
        source.sendSuccess(() -> CommandFormat.detail(session.getStatus().name().toLowerCase(Locale.ROOT)
                + " · " + Math.round(session.getCurrentHealth()) + "/" + Math.round(session.getMaxHealth()) + " hp"
                + " · " + session.getActiveParticipants().size() + "/" + session.getParticipants().size() + " players"
                + " · " + timer + (session.isFleeAllowed() ? " · flee on" : "")), false);

        Map<UUID, Double> shares = ContributionMath.percentages(
                session.getContributionSnapshot(), session.getActiveParticipants());
        if (shares.isEmpty()) return;
        // One line for all contributors rather than one line each: a 4-player raid was 4 rows deep.
        StringBuilder line = new StringBuilder();
        for (Map.Entry<UUID, Double> entry : shares.entrySet()) {
            ServerPlayer player = source.getServer().getPlayerList().getPlayer(entry.getKey());
            String name = player == null ? entry.getKey().toString().substring(0, 8)
                    : player.getGameProfile().getName();
            if (line.length() > 0) line.append(" · ");
            line.append(name).append(' ').append(CommandFormat.percent(entry.getValue()));
        }
        source.sendSuccess(() -> CommandFormat.detail(line.toString()), false);
    }

    static int history(CommandSourceStack source) {
        List<RaidSpawnHistory.Entry> entries = RaidSpawnHistory.recent();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No recorded natural-spawn attempts yet.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> CommandFormat.header("Recent wild-spawn attempts (" + entries.size()
                + ", oldest first)"), false);
        // The scheduler retries on a fixed interval, so the same outcome usually repeats for as long
        // as its cause lasts -- a blocked spawn can easily fill the whole buffer with one identical
        // reason. Consecutive runs of the same player/dimension/outcome collapse to a single row
        // with a time span and a count, so what is on screen is the sequence of distinct events.
        int index = 0;
        int rows = 0;
        while (index < entries.size()) {
            RaidSpawnHistory.Entry first = entries.get(index);
            int end = index + 1;
            while (end < entries.size() && sameRun(first, entries.get(end))) end++;

            RaidSpawnHistory.Entry last = entries.get(end - 1);
            int count = end - index;
            String when = count == 1
                    ? CommandFormat.seconds(first.tick() / 20.0)
                    : CommandFormat.seconds(first.tick() / 20.0).replace("s", "")
                            + "-" + CommandFormat.seconds(last.tick() / 20.0);
            boolean success = first.outcome() == RaidSpawnHistory.Outcome.SUCCESS;

            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(when, 10)
                            + CommandFormat.pad(first.player(), 12)
                            + first.outcome().name().toLowerCase(Locale.ROOT)
                            + (count == 1 ? "" : " x" + count))
                    .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            // The reason is the useful half of a failure, so it keeps its own dimmed line -- but
            // only once per run, showing the most recent of them.
            source.sendSuccess(() -> CommandFormat.detail(CommandFormat.shortId(last.dimension())
                    + " · " + last.detail()), false);
            index = end;
            rows++;
        }
        return rows;
    }

    /**
     * Keys are printed exactly as they appear in server.json, not as the Java accessor names.
     * The point of this command is to find the value you then go and edit, and the old output
     * showed "checkIntervalTicks" for a field the file calls "check_interval_ticks" -- which an
     * operator cannot search the file for.
     */
    static int config(CommandSourceStack source) {
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

        section(source, "other");
        setting(source, "tier_scaling.enabled", config.tierScaling().enabled());
        setting(source, "debug_logging", config.debugLogging());
        return 1;
    }

    /** Same player, dimension and outcome -- the detail (a candidate position) is expected to differ. */
    private static boolean sameRun(RaidSpawnHistory.Entry a, RaidSpawnHistory.Entry b) {
        return a.outcome() == b.outcome()
                && a.player().equals(b.player())
                && a.dimension().equals(b.dimension());
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
