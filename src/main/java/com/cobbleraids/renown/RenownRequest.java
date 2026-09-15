package com.cobbleraids.renown;

import java.util.Locale;
import java.util.Optional;

/** How a spawn decides renown: the configured chance, always, or never. The last two are for operators. */
public enum RenownRequest {
    ROLL, FORCE, NONE;

    public String serializedName() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<RenownRequest> parse(String value) {
        if (value == null) return Optional.empty();
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "roll", "random" -> Optional.of(ROLL);
            case "force", "yes", "on" -> Optional.of(FORCE);
            case "none", "no", "off" -> Optional.of(NONE);
            default -> Optional.empty();
        };
    }
}
