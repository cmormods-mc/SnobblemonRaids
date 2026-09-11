package com.cobbleraids;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place CobbleRaids writes to the server log.
 *
 * <p>This mod runs on boxes nobody is watching, where the log is the only account of what happened,
 * and until now it was written three different ways: {@code System.out.println}, {@code System.err}
 * with a separate {@code printStackTrace()}, and a handful of SLF4J loggers -- of which some
 * prefixed {@code [CobbleRaids]} and some did not. Three problems followed from that.
 *
 * <p>First, level. Minecraft's log4j captures stdout and stderr and re-emits them as {@code [STDOUT]}
 * and {@code [STDERR]}, so a genuine error printed to {@code System.err} arrives looking like
 * console noise, and no appender or log-level configuration can tell it apart from a debug line.
 * Going through a logger means an error is logged at ERROR.
 *
 * <p>Second, a detached stack trace. {@code printStackTrace()} writes straight to the process's
 * stderr on whatever thread threw, so on a busy server it interleaves with unrelated output and
 * lands separately from the message explaining it. Passing the throwable to the logger keeps the two
 * together.
 *
 * <p>Third, grep. Minecraft's log pattern prints the thread and the level but <em>not</em> the logger
 * name -- verified against a real server log -- so an operator has no way to isolate this mod's
 * lines except by a marker in the message itself. That is why {@link #PREFIX} is still applied here
 * rather than dropped in favour of the logger name: it is what makes {@code grep "[CobbleRaids]"}
 * work, and applying it in one place is what makes it consistent.
 *
 * <p>Messages use SLF4J's {@code {}} placeholders. A trailing {@link Throwable} is logged as the
 * exception rather than as a parameter, which is SLF4J's own convention.
 */
public final class RaidLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleRaids");

    /** Kept in the message because the log pattern does not print the logger name. */
    private static final String PREFIX = "[CobbleRaids] ";

    private RaidLog() {}

    /** Ordinary operational events: a raid spawned, rewards restored, config migrated. */
    public static void info(String message, Object... args) {
        LOGGER.info(PREFIX + message, args);
    }

    /** A configuration or content problem an operator should fix, which the mod worked around. */
    public static void warn(String message, Object... args) {
        LOGGER.warn(PREFIX + message, args);
    }

    /** Something failed. Pass the throwable last so the stack trace stays with its message. */
    public static void error(String message, Object... args) {
        LOGGER.error(PREFIX + message, args);
    }
}
