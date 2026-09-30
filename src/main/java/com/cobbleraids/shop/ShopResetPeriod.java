package com.cobbleraids.shop;

import java.time.Instant;
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
    DAILY,
    /** The limit refills at 00:00 UTC on Monday. */
    WEEKLY,
    /** The limit refills every four hours, in step with the rotating Pokemon page. */
    ROTATION;

    /** Seconds in one rotation of the Pokemon page. */
    public static final long ROTATION_SECONDS = 4L * 3600L;

    /** Seconds in a day. UTC has no daylight saving, so every day is exactly this long. */
    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * Which window {@code instant} falls in. Always 0 for a limit that never resets.
     *
     * <p>Arithmetic rather than {@code atOffset(UTC).toLocalDate().toEpochDay()}, which allocates an
     * OffsetDateTime and a LocalDate each time. That is charged once per cell every time a page is
     * sent, so sixty-four of each per click, for a number that is a division. The two agree exactly
     * -- an epoch day in UTC is defined as this division -- and ShopResetPeriodTest holds them to
     * it across a span of years rather than taking it on trust.
     */
    public long windowOf(Instant instant) {
        long day = Math.floorDiv(instant.getEpochSecond(), SECONDS_PER_DAY);
        return switch (this) {
            case NEVER -> 0L;
            case DAILY -> day;
            case WEEKLY -> weekOfDay(day);
            case ROTATION -> Math.floorDiv(instant.getEpochSecond(), ROTATION_SECONDS);
        };
    }

    /**
     * The Monday-to-Sunday week an epoch day falls in. Day 0 (1970-01-01) was a Thursday, so a week
     * starts on day 4, 11, 18... Week numbers are around 2,900 today and day numbers around 20,000,
     * so a stored weekly tally can never be mistaken for a daily one; see
     * {@code RaidPlayerRecord.prunePurchases}, which relies on that.
     */
    public static long weekOfDay(long epochDay) {
        return Math.floorDiv(epochDay - 4L, 7L);
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
            case "weekly", "week" -> WEEKLY;
            case "rotation" -> ROTATION;
            default -> fallback;
        };
    }

    /** "today" or "ever" -- how a refusal should describe the window to a player. */
    public String windowNoun() {
        return switch (this) {
            case NEVER -> "ever";
            case DAILY -> "today";
            case WEEKLY -> "this week";
            case ROTATION -> "this rotation";
        };
    }
}
