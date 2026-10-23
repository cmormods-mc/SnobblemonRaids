package com.cobbleraids.perf;

import com.cobbleraids.RaidLog;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.raid.RaidRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Turns {@link RaidPerf}'s running totals into the release-day record: one summary line in the server
 * log and a block of rows appended to {@code logs/cobbleraids-perf.csv} every five minutes.
 *
 * <p>The CSV is long-format (one row per section per window) so a section that first appears at
 * 9pm does not change the columns, and so a spreadsheet pivot or a few lines of Python can plot any
 * section against the player count. Gauges (players, MSPT, heap, live raids) ride in the same file so
 * a spike in a section can be read against what the server was doing at that moment.
 *
 * <p>An idle server writes nothing: a window with nobody online and no recorded sections is skipped,
 * so a 24/7 box does not fill the file overnight.
 */
public final class RaidPerfReporter {
    private static final long INTERVAL_TICKS = 20L * 60L * 5L;
    private static final String FILE_NAME = "cobbleraids-perf.csv";

    private static long tickCounter;

    private RaidPerfReporter() {}

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % INTERVAL_TICKS) != 0L) return;
        report(server);
    }

    /** Writes the current window now, whatever the clock says. Returns where it went, or null on failure. */
    public static Path report(MinecraftServer server) {
        int players = server.getPlayerCount();
        List<RaidPerf.Row> window = RaidPerf.window(true);
        if (players == 0 && window.isEmpty()) return null;

        String now = Instant.now().toString();
        List<String> lines = new ArrayList<>(RaidPerf.csvLines(now, window));
        for (Gauge gauge : gauges(server)) lines.add(RaidPerf.gaugeLine(now, gauge.name(), gauge.value()));

        RaidLog.info("[perf] last 5 min: {}", summary(server, window));
        Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.writeString(file, RaidPerf.CSV_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return file;
        } catch (IOException ex) {
            RaidLog.warn("[perf] could not append to {}: {}", file, ex.toString());
            return null;
        }
    }

    public record Gauge(String name, double value) {}

    /** Point-in-time readings, in the order they are printed. */
    public static List<Gauge> gauges(MinecraftServer server) {
        Runtime runtime = Runtime.getRuntime();
        double heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
        return List.of(
                new Gauge("players", server.getPlayerCount()),
                new Gauge("mspt", server.getAverageTickTimeNanos() / 1_000_000.0),
                new Gauge("heap_mb", heapMb),
                new Gauge("active_raids", RaidRegistry.all().size()),
                new Gauge("open_lobbies", RaidLobbyManager.all().size()));
    }

    /** The single log line: the readings, then the three sections that cost the most this window. */
    static String summary(MinecraftServer server, List<RaidPerf.Row> window) {
        StringBuilder out = new StringBuilder();
        for (Gauge gauge : gauges(server)) {
            out.append(gauge.name()).append('=').append(String.format(Locale.ROOT, "%.1f", gauge.value())).append(' ');
        }
        out.append("| top sections:");
        int shown = 0;
        for (RaidPerf.Row row : window) {
            if (row.totalNanos() <= 0L) continue;
            out.append(String.format(Locale.ROOT, " %s %.1fms total/%.2fms max (x%d)",
                    row.name(), row.totalMillis(), row.maxMillis(), row.count()));
            if (++shown == 3) break;
        }
        if (shown == 0) out.append(" none");
        return out.toString();
    }

    public static void onServerStopped() {
        tickCounter = 0L;
    }
}
