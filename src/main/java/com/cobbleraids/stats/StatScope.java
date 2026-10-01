package com.cobbleraids.stats;

import java.util.Locale;
import java.util.Optional;

/** Which span of time a board covers. */
public enum StatScope {
    ALL_TIME("alltime"),
    /** The current Monday-to-Sunday week in UTC: the same window the shop's weekly purchase limits use. */
    WEEKLY("weekly");

    private final String serializedName;

    StatScope(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static Optional<StatScope> parse(String value) {
        if (value == null) return Optional.empty();
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "alltime", "all_time", "all-time", "all" -> Optional.of(ALL_TIME);
            case "weekly", "week" -> Optional.of(WEEKLY);
            default -> Optional.empty();
        };
    }
}
