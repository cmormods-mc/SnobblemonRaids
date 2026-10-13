package com.cobbleraids.web;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The rarity tiers as a page needs them: id to send back, name to show, colour to paint. */
final class WebTiers {
    private WebTiers() {}

    /** Every tier in filter-cycling order, each {@code {id, name, color}}. */
    static JsonArray all() {
        JsonArray tiers = new JsonArray();
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tier.serializedName());
            entry.addProperty("name", tier.displayName());
            entry.addProperty("color", color(tier));
            tiers.add(entry);
        }
        return tiers;
    }

    /** The tier's display colour as CSS. */
    static String color(RaidRarityTier tier) {
        Integer rgb = RaidTierPresentation.color(tier).getColor();
        return String.format("#%06x", rgb == null ? 0x39F2FF : rgb);
    }

    /** Resolves a serialized tier name, or null if it names none. */
    static RaidRarityTier parse(String value) {
        try {
            return RaidRarityTier.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
