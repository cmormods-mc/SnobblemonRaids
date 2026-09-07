package com.cobbleraids.presentation;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared look for operator command output. Minecraft chat is narrow and wraps mid-field, so every
 * helper here exists to keep a row short: strip namespaces that are the same on every line, round
 * coordinates an operator only reads approximately, and let color carry state instead of a
 * "key=" label. Columns are space-padded, which the variable-width chat font only approximates --
 * good enough to scan down, which is the point.
 */
public final class CommandFormat {
    private CommandFormat() {}

    /** Headers are the only bold element, so a block of output has exactly one obvious anchor. */
    public static MutableComponent header(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    public static MutableComponent row(String text) {
        return Component.literal(" " + text);
    }

    /** Secondary detail under a row; dimmed so the rows above stay the thing you scan. */
    public static MutableComponent detail(String text) {
        return Component.literal("   " + text).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * Drops the namespace when it is one every row shares anyway. A third-party namespace is kept,
     * because there it is the only thing distinguishing the entry.
     */
    public static String shortId(ResourceLocation id) {
        if (id == null) return "unknown";
        String namespace = id.getNamespace();
        return namespace.equals("cobbleraids") || namespace.equals("minecraft") ? id.getPath() : id.toString();
    }

    /** Whole blocks: an operator uses these to teleport or eyeball distance, never to sub-block precision. */
    public static String coords(double x, double y, double z) {
        return Math.round(x) + " " + Math.round(y) + " " + Math.round(z);
    }

    public static String seconds(double value) {
        return String.format(Locale.ROOT, "%.0fs", value);
    }

    public static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /**
     * Human-scale duration: 45s, 12m 30s, 2h 5m. Spawn cooldowns run to hours, where a raw second
     * count stops being something you can read at a glance.
     */
    public static String duration(long totalSeconds) {
        if (totalSeconds < 60L) return totalSeconds + "s";
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes < 60L) return seconds == 0L ? minutes + "m" : minutes + "m " + seconds + "s";
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        return remainingMinutes == 0L ? hours + "h" : hours + "h " + remainingMinutes + "m";
    }

    /**
     * Coordinates that teleport the clicker, with the exact position on hover.
     *
     * Only ever used inside operator-only output: the click runs /tp, which is itself permission
     * gated, so a non-operator could not act on this even if it reached them. The rounded text stays
     * as the label because that is the readable part; the hover carries the precision that rounding
     * drops, which is what you want when a boss is wedged inside terrain.
     */
    public static MutableComponent teleport(String dimension, double x, double y, double z) {
        String exact = String.format(Locale.ROOT, "%.2f %.2f %.2f", x, y, z);
        String command = String.format(Locale.ROOT, "/execute in %s run tp @s %.2f %.2f %.2f",
                dimension, x, y, z);
        return Component.literal(coords(x, y, z)).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Teleport to " + exact))));
    }

    /** Right-pads to a column width, leaving longer values intact rather than truncating a name. */
    public static String pad(String value, int width) {
        if (value.length() >= width) return value + " ";
        return value + " ".repeat(width - value.length());
    }

    /**
     * "a, b, c +12" -- keeps a chat line to one line. Showing a few real names is what makes the
     * count meaningful, so the limit is about fitting, not about hiding.
     */
    public static String names(List<String> values, int shown) {
        if (values.isEmpty()) return "none";
        if (values.size() <= shown) return String.join(", ", values);
        return String.join(", ", values.subList(0, shown)) + " +" + (values.size() - shown);
    }
}
