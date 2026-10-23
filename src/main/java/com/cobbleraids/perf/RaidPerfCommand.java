package com.cobbleraids.perf;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * {@code /cobbleraids perf}: what this mod has cost the server, without leaving the game.
 *
 * <p>Shows the live readings and the busiest sections since the server started. {@code perf log}
 * writes the current window to the CSV straight away, which is what to run just before and just after
 * a busy moment on release day so the file brackets it exactly.
 */
public final class RaidPerfCommand {
    private static final int ROWS = 12;

    private RaidPerfCommand() {}

    public static int show(CommandSourceStack source) {
        for (RaidPerfReporter.Gauge gauge : RaidPerfReporter.gauges(source.getServer())) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%-13s %.1f", gauge.name(), gauge.value()))
                    .withStyle(ChatFormatting.AQUA), false);
        }
        List<RaidPerf.Row> rows = RaidPerf.lifetime();
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nothing has been timed yet.").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%-26s %8s %10s %9s %8s",
                "section", "runs", "total ms", "avg ms", "max ms")).withStyle(ChatFormatting.GOLD), false);
        int shown = 0;
        for (RaidPerf.Row row : rows) {
            if (shown++ == ROWS) break;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%-26s %8d %10.1f %9.3f %8.2f",
                    row.name(), row.count(), row.totalMillis(), row.averageMillis(), row.maxMillis())), false);
        }
        return rows.size();
    }

    public static int log(CommandSourceStack source) {
        Path file = RaidPerfReporter.report(source.getServer());
        if (file == null) {
            source.sendFailure(Component.literal("Nothing to write yet, or the file could not be written; see the server log."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Wrote the current window to " + file).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
