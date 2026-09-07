package com.cobbleraids.presentation;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
