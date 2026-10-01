package com.cobbleraids.command;

import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.shop.ShopResetPeriod;
import com.cobbleraids.stats.LeaderboardStat;
import com.cobbleraids.stats.Leaderboards;
import com.cobbleraids.stats.PlayerStats;
import com.cobbleraids.stats.RaidStats;
import com.cobbleraids.stats.RaidStatsStore;
import com.cobbleraids.stats.RaidTotals;
import com.cobbleraids.stats.StatBlock;
import com.cobbleraids.stats.StatScope;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The leaderboards and personal statistics, for everyone.
 *
 * <p>Read-only and open to every player, unlike the admin tooling beside it: a leaderboard nobody can
 * look at is not one. Both commands only read the in-memory book, and a board is sorted when it is
 * asked for, so the cost is one pass over the players the server has seen, paid by the asker.
 *
 * <p>This is the text face of {@link Leaderboards}, which a screen can call directly.
 */
public final class RaidStatsCommand {

    private static final int DEFAULT_ROWS = 10;
    private static final int MAX_ROWS = 25;

    private static final SuggestionProvider<CommandSourceStack> STAT_IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(LeaderboardStat.ids(), builder);
    private static final SuggestionProvider<CommandSourceStack> SCOPES = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("weekly", "alltime"), builder);

    private RaidStatsCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("top")
                                .executes(context -> listBoards(context.getSource()))
                                .then(Commands.argument("stat", StringArgumentType.word())
                                        .suggests(STAT_IDS)
                                        .executes(context -> board(context.getSource(),
                                                StringArgumentType.getString(context, "stat"), "alltime", DEFAULT_ROWS))
                                        .then(Commands.argument("scope", StringArgumentType.word())
                                                .suggests(SCOPES)
                                                .executes(context -> board(context.getSource(),
                                                        StringArgumentType.getString(context, "stat"),
                                                        StringArgumentType.getString(context, "scope"), DEFAULT_ROWS))
                                                .then(Commands.argument("rows", IntegerArgumentType.integer(1, MAX_ROWS))
                                                        .executes(context -> board(context.getSource(),
                                                                StringArgumentType.getString(context, "stat"),
                                                                StringArgumentType.getString(context, "scope"),
                                                                IntegerArgumentType.getInteger(context, "rows")))))))
                        .then(Commands.literal("stats")
                                .executes(context -> playerStats(context.getSource(), context.getSource().getPlayerOrException()))
                                .then(Commands.literal("server")
                                        .executes(context -> serverStats(context.getSource())))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> playerStats(context.getSource(),
                                                EntityArgument.getPlayer(context, "target")))))));
    }

    // ---------------------------------------------------------------- top

    private static int listBoards(CommandSourceStack source) {
        source.sendSuccess(() -> CommandFormat.header("Leaderboards"), false);
        for (LeaderboardStat stat : LeaderboardStat.values()) {
            source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(stat.id(), 18) + stat.title()), false);
        }
        source.sendSuccess(() -> CommandFormat.hint("/cobbleraids top <board> [weekly|alltime] [rows]"), false);
        return LeaderboardStat.values().length;
    }

    private static int board(CommandSourceStack source, String statId, String scopeId, int rows) {
        LeaderboardStat stat = LeaderboardStat.parse(statId).orElse(null);
        StatScope scope = StatScope.parse(scopeId).orElse(null);
        if (stat == null) {
            source.sendFailure(Component.literal("No such board: " + statId + ". Try /cobbleraids top."));
            return 0;
        }
        if (scope == null) {
            source.sendFailure(Component.literal("Choose weekly or alltime, not " + scopeId + "."));
            return 0;
        }
        UUID viewer = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        long window = RaidStats.window();
        Leaderboards.Board board = Leaderboards.board(RaidStatsStore.book(), stat, scope, window, rows, viewer,
                id -> RaidStats.nameOf(source.getServer(), id));

        String span = scope == StatScope.WEEKLY ? "this week, resets in " + weekResetIn() : "all time";
        source.sendSuccess(() -> CommandFormat.header(stat.title() + "  ·  " + span), false);
        if (board.rows().isEmpty()) {
            source.sendSuccess(() -> CommandFormat.row("Nobody is on this board yet."), false);
            return 0;
        }
        for (Leaderboards.Row row : board.rows()) {
            source.sendSuccess(() -> rowLine(row, stat, row.player().equals(viewer)), false);
        }
        if (board.viewer() != null && board.viewer().rank() > board.rows().size()) {
            source.sendSuccess(() -> Component.literal("  …").withStyle(ChatFormatting.DARK_GRAY), false);
            source.sendSuccess(() -> rowLine(board.viewer(), stat, true), false);
        }
        source.sendSuccess(() -> CommandFormat.hint(" " + board.ranked() + " ranked"), false);
        return board.rows().size();
    }

    private static Component rowLine(Leaderboards.Row row, LeaderboardStat stat, boolean own) {
        String text = CommandFormat.pad("#" + row.rank(), 5) + CommandFormat.pad(row.name(), 18) + format(stat, row.value());
        return own ? CommandFormat.row(text).copy().withStyle(ChatFormatting.AQUA) : CommandFormat.row(text);
    }

    // ---------------------------------------------------------------- stats

    private static int playerStats(CommandSourceStack source, ServerPlayer target) {
        PlayerStats stats = RaidStatsStore.book().get(target.getUUID());
        long window = RaidStats.window();
        source.sendSuccess(() -> CommandFormat.header("Raid statistics  " + target.getGameProfile().getName()), false);
        block(source, "all time", stats.allTime());
        block(source, "this week", stats.weekIn(window));
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("streak", 11) + stats.currentStreak() + " win(s) running"), false);
        return stats.allTime().raidsWon();
    }

    private static void block(CommandSourceStack source, String title, StatBlock block) {
        source.sendSuccess(() -> CommandFormat.hint(" " + title), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("raids", 11) + block.raidsWon() + " won · "
                + block.raidsLost() + " lost · " + block.raidsFled() + " fled · " + block.raidsJoined() + " joined"), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("tiers", 11) + "starter " + block.winsStarter()
                + " · powerhouse " + block.winsPowerhouse() + " · legendary " + block.winsLegendary()
                + " · mythical " + block.winsMythical()), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("damage", 11) + String.format(Locale.ROOT, "%,d", block.totalDamage())
                + " total · best raid " + String.format(Locale.ROOT, "%,d", block.bestRaidDamage())), false);
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad("other", 11) + String.format(Locale.ROOT, "%,d", block.rpEarned())
                + " RP earned · " + block.bossesCaught() + " caught · " + block.renownDefeated() + " renowned"
                + " · best streak " + block.bestWinStreak()
                + (block.fastestWinSeconds() > 0 ? " · fastest " + CommandFormat.duration(block.fastestWinSeconds()) : "")), false);
    }

    private static int serverStats(CommandSourceStack source) {
        long window = RaidStats.window();
        RaidTotals all = RaidStatsStore.book().allTimeTotals();
        RaidTotals week = RaidStatsStore.book().weekTotals(window);
        source.sendSuccess(() -> CommandFormat.header("Server raid statistics"), false);
        totals(source, "all time", all);
        totals(source, "this week", week);
        source.sendSuccess(() -> CommandFormat.hint(" " + RaidStatsStore.book().players().size() + " player(s) on record"), false);
        return all.won();
    }

    private static void totals(CommandSourceStack source, String title, RaidTotals totals) {
        source.sendSuccess(() -> CommandFormat.row(CommandFormat.pad(title, 11) + totals.started() + " started · "
                + totals.won() + " won · " + totals.lost() + " lost · " + totals.caught() + " caught · "
                + String.format(Locale.ROOT, "%,d", totals.totalDamage()) + " damage · "
                + String.format(Locale.ROOT, "%,d", totals.rpPaid()) + " RP paid"), false);
    }

    // ---------------------------------------------------------------- formatting

    private static String format(LeaderboardStat stat, long value) {
        return stat.kind() == LeaderboardStat.Kind.SECONDS
                ? CommandFormat.duration(value) : String.format(Locale.ROOT, "%,d", value);
    }

    /** How long until the weekly boards turn over: the next Monday 00:00 UTC. */
    private static String weekResetIn() {
        Instant now = Instant.now();
        long window = ShopResetPeriod.WEEKLY.windowOf(now);
        long day = now.getEpochSecond() / 86_400L;
        while (ShopResetPeriod.weekOfDay(day) == window) day++;
        return CommandFormat.duration(day * 86_400L - now.getEpochSecond());
    }
}
