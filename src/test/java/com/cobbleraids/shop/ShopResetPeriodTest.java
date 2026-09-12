package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The window calculation a daily limit is built on.
 *
 * <p>{@code windowOf} divides rather than going through OffsetDateTime and LocalDate, because it is
 * charged once per cell every time a page is sent. That is only worth doing if the two are exactly
 * the same answer, so this holds them to it across a span of years rather than taking it on trust.
 */
class ShopResetPeriodTest {

    /** What the readable implementation would have returned. */
    private static long viaCalendar(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    @Test
    @DisplayName("the arithmetic agrees with the calendar, hour by hour, over several years")
    void agreesWithTheCalendar() {
        Instant cursor = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2029-01-01T00:00:00Z");
        while (cursor.isBefore(end)) {
            assertEquals(viaCalendar(cursor), ShopResetPeriod.DAILY.windowOf(cursor),
                    "disagreed at " + cursor);
            cursor = cursor.plus(7, ChronoUnit.HOURS);
        }
    }

    @Test
    @DisplayName("it agrees either side of a leap day and a leap second's worth of drift")
    void agreesAtAwkwardDates() {
        for (String moment : new String[] {
                "2024-02-28T23:59:59Z", "2024-02-29T00:00:00Z", "2024-02-29T23:59:59Z",
                "2024-03-01T00:00:00Z", "2016-12-31T23:59:59Z", "2017-01-01T00:00:00Z"}) {
            Instant instant = Instant.parse(moment);
            assertEquals(viaCalendar(instant), ShopResetPeriod.DAILY.windowOf(instant), moment);
        }
    }

    @Test
    @DisplayName("it agrees before the epoch, where truncating division would not")
    void agreesBeforeTheEpoch() {
        // floorDiv, not integer division: a negative second divided the other way rounds towards
        // zero and puts the last day of 1969 in the same window as the first of 1970.
        Instant lastDayOf1969 = Instant.parse("1969-12-31T12:00:00Z");
        Instant firstDayOf1970 = Instant.parse("1970-01-01T12:00:00Z");

        assertEquals(viaCalendar(lastDayOf1969), ShopResetPeriod.DAILY.windowOf(lastDayOf1969));
        assertEquals(-1L, ShopResetPeriod.DAILY.windowOf(lastDayOf1969));
        assertEquals(0L, ShopResetPeriod.DAILY.windowOf(firstDayOf1970));
    }

    @Test
    @DisplayName("midnight UTC is the boundary, to the second")
    void midnightIsTheBoundary() {
        assertEquals(ShopResetPeriod.DAILY.windowOf(Instant.parse("2026-09-12T00:00:00Z")),
                ShopResetPeriod.DAILY.windowOf(Instant.parse("2026-09-12T23:59:59Z")));
        assertEquals(ShopResetPeriod.DAILY.windowOf(Instant.parse("2026-09-12T23:59:59Z")) + 1,
                ShopResetPeriod.DAILY.windowOf(Instant.parse("2026-09-13T00:00:00Z")));
    }

    @Test
    @DisplayName("a limit that never resets has one window for all time")
    void neverHasOneWindow() {
        assertEquals(0L, ShopResetPeriod.NEVER.windowOf(Instant.EPOCH));
        assertEquals(0L, ShopResetPeriod.NEVER.windowOf(Instant.parse("2099-12-31T23:59:59Z")));
        assertFalse(ShopResetPeriod.NEVER.resets());
        assertTrue(ShopResetPeriod.DAILY.resets());
    }

    @Test
    @DisplayName("names round-trip, and an unknown one falls back rather than throwing")
    void parsing() {
        for (ShopResetPeriod period : ShopResetPeriod.values()) {
            assertEquals(period, ShopResetPeriod.parse(period.serializedName(), null));
        }
        assertEquals(ShopResetPeriod.NEVER, ShopResetPeriod.parse("NEVER", null));
        assertEquals(ShopResetPeriod.NEVER, ShopResetPeriod.parse("  once  ", null));
        assertEquals(ShopResetPeriod.DAILY, ShopResetPeriod.parse("weekly", ShopResetPeriod.DAILY));
        assertEquals(ShopResetPeriod.DAILY, ShopResetPeriod.parse(null, ShopResetPeriod.DAILY));
    }
}
