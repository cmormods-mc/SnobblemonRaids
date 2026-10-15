package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.RequestThrottle;
import com.cobbleraids.stats.LeaderboardStat;
import com.cobbleraids.stats.Leaderboards;
import com.cobbleraids.stats.RaidStats;
import com.cobbleraids.stats.RaidStatsStore;
import com.cobbleraids.stats.StatScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * The leaderboards as a web page: any of the boards, all time or this week, with the viewer's own
 * standing. The page asks for a board by id and scope and the server ranks it from the stats book via
 * {@link Leaderboards#board}, the same call {@code /cobbleraids top} uses; nothing is ranked in the page.
 */
public final class LeaderboardWebPage {
    private LeaderboardWebPage() {}

    public static final String CHANNEL = "cobbleraids.leaders";
    public static final String PAGE = "leaders/leaders.html";
    private static final int ROWS = 10;

    /** A board is a read of every player the server has seen; this only stops a page hammering it. */
    private static final RequestThrottle THROTTLE = new RequestThrottle(10, 1000);

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("leaders-web-event", () -> onEvent(player, json)));
    }

    /** Forgets a disconnected player's request budget. */
    public static void forget(UUID player) {
        THROTTLE.forget(player);
    }

    public static boolean open(ServerPlayer player) {
        return WebUi.open(player, PAGE);
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        String action = WebJson.string(message, "action");
        if (!THROTTLE.allow(player.getUUID(), System.currentTimeMillis())) return;
        switch (action) {
            case "ready" -> send(player, LeaderboardStat.RAIDS_WON, StatScope.ALL_TIME);
            case "query" -> {
                LeaderboardStat stat = LeaderboardStat.parse(WebJson.string(message, "stat"))
                        .orElse(LeaderboardStat.RAIDS_WON);
                StatScope scope = StatScope.parse(WebJson.string(message, "scope")).orElse(StatScope.ALL_TIME);
                send(player, stat, scope);
            }
            default -> RaidLog.warn("Ignoring unknown leaderboard page action from {}",
                    player.getGameProfile().getName());
        }
    }

    private static void send(ServerPlayer player, LeaderboardStat stat, StatScope scope) {
        Leaderboards.Board board = Leaderboards.board(RaidStatsStore.book(), stat, scope, RaidStats.window(), ROWS,
                player.getUUID(), id -> RaidStats.nameOf(player.getServer(), id));
        JsonObject out = new JsonObject();
        out.addProperty("stat", stat.id());
        out.addProperty("title", stat.title());
        out.addProperty("seconds", stat.kind() == LeaderboardStat.Kind.SECONDS);
        out.addProperty("scope", scope.serializedName());
        out.addProperty("ranked", board.ranked());
        if (scope == StatScope.WEEKLY) out.addProperty("resetsInSeconds", RaidStats.secondsUntilWeekReset());
        JsonArray boards = new JsonArray();
        for (LeaderboardStat each : LeaderboardStat.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", each.id());
            entry.addProperty("title", each.title());
            boards.add(entry);
        }
        out.add("boards", boards);
        JsonArray rows = new JsonArray();
        for (Leaderboards.Row row : board.rows()) rows.add(rowJson(row, player.getUUID()));
        out.add("rows", rows);
        if (board.viewer() != null) out.add("viewer", rowJson(board.viewer(), player.getUUID()));
        WebGuiBridge.emit(player, "leaders_page", out.toString());
    }

    private static JsonObject rowJson(Leaderboards.Row row, UUID viewer) {
        JsonObject out = new JsonObject();
        out.addProperty("rank", row.rank());
        out.addProperty("name", row.name());
        out.addProperty("value", row.value());
        out.addProperty("you", row.player().equals(viewer));
        return out;
    }
}
