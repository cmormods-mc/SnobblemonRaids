package com.cobbleraids.shop;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * How often a purchase limit refills.
 *
 * <p>Days are real days, not Minecraft days. A Minecraft day is twenty minutes, which would turn a
 * "five a day" limit into fifteen an hour, and it also stops whenever nobody is online -- so two
 * servers with the same catalogue would hand out different amounts depending on how busy they are.
 * A shop limit is a real-world pacing tool, so it uses a real-world clock.
 *
 * <p>UTC rather than the machine's zone, so the reset happens at the same moment for every player
 * on a server and does not move when the host does.
 */
public enum ShopResetPeriod {

    /** The limit is for the life of the player. Buy it once, that is it. */
    NEVER,
    /** The limit refills at 00:00 UTC. */
    DAILY;

    /** Which window {@code instant} falls in. Always 0 for a limit that never resets. */
    public long windowOf(Instant instant) {
        return this == DAILY ? instant.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay() : 0L;
    }

    public boolean resets() {
        return this != NEVER;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown values fall back to {@code fallback} rather than throwing; see ShopCatalog. */
    public static ShopResetPeriod parse(String value, ShopResetPeriod fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "never", "none", "once" -> NEVER;
            case "daily", "day" -> DAILY;
            default -> fallback;
        };
    }

    /** "today" or "ever" -- how a refusal should describe the window to a player. */
    public String windowNoun() {
        return this == DAILY ? "today" : "ever";
    }
}
