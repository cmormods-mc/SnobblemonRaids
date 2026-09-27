package com.cobbleraids.title;

import java.util.Locale;

/**
 * The closed set of stats a title can be unlocked against, all read from {@code RaidPlayerRecord}.
 * Deliberately small and closed rather than an open scripting condition -- an operator editing
 * titles.json chooses from a short list, and this class is the only place that needs to grow if a
 * new stat is ever worth gating a title on.
 */
public enum TitleUnlockType {
    /** Total raids won, of any tier. */
    RAIDS_WON,
    /** Raids won of one specific {@code RaidRarityTier}, named by the title's own {@code tier} field. */
    TIER_WINS,
    /** Total bosses caught via the personal-shop/catching path. */
    BOSSES_CAUGHT;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TitleUnlockType parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
