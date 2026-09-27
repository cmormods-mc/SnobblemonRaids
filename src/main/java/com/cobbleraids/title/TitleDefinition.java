package com.cobbleraids.title;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

/**
 * One row of {@code config/cobbleraids/titles.json}: an id, what to show, and the one stat that
 * unlocks it.
 *
 * <p>{@code color} names a vanilla {@code ChatFormatting} constant (e.g. {@code "GOLD"}) rather than
 * storing the enum itself, the same reason {@link com.cobbleraids.shop.ShopCatalog}'s own header
 * comment gives for staying free of Minecraft types: this is parsed and tested without a server, and
 * only resolved to a real {@code ChatFormatting} where it is actually drawn.
 *
 * <p>{@code tier} is only read for {@link TitleUnlockType#TIER_WINS} and is {@code null} otherwise --
 * a title gated on total wins or total catches has no tier to name.
 */
public record TitleDefinition(
        String id,
        String display,
        String color,
        TitleUnlockType unlockType,
        RaidRarityTier tier,
        int threshold
) {
    /**
     * Reads one title, dropping it (returns {@code null}) rather than failing the whole catalogue --
     * the same policy {@code ShopSection.fromJson} follows, for the same reason: one operator typo
     * should cost one title, not every title after it in the file.
     */
    public static TitleDefinition fromJson(JsonObject json) {
        String id = json.has("id") ? json.get("id").getAsString() : "";
        if (id.isEmpty()) {
            RaidLog.error("titles.json: dropping a title with no id");
            return null;
        }
        String display = json.has("display") ? json.get("display").getAsString() : id;
        String color = json.has("color") ? json.get("color").getAsString() : "WHITE";
        TitleUnlockType unlockType;
        try {
            unlockType = TitleUnlockType.parse(json.has("unlock_type") ? json.get("unlock_type").getAsString() : "");
        } catch (RuntimeException ex) {
            RaidLog.error("titles.json: dropping title '" + id + "' with an unrecognised unlock_type");
            return null;
        }
        RaidRarityTier tier = null;
        if (unlockType == TitleUnlockType.TIER_WINS) {
            try {
                tier = RaidRarityTier.parse(json.has("tier") ? json.get("tier").getAsString() : "");
            } catch (RuntimeException ex) {
                RaidLog.error("titles.json: dropping title '" + id + "': tier_wins needs a valid tier");
                return null;
            }
        }
        int threshold = json.has("threshold") ? json.get("threshold").getAsInt() : 0;
        if (threshold <= 0) {
            RaidLog.error("titles.json: dropping title '" + id + "' with a non-positive threshold");
            return null;
        }
        return new TitleDefinition(id, display, color, unlockType, tier, threshold);
    }

    /** {@link #color}, resolved to a real {@code ChatFormatting}, falling back to white if unknown. */
    public ChatFormatting chatColor() {
        ChatFormatting resolved = ChatFormatting.getByName(color);
        return resolved != null ? resolved : ChatFormatting.WHITE;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("display", display);
        json.addProperty("color", color);
        json.addProperty("unlock_type", unlockType.serializedName());
        if (tier != null) json.addProperty("tier", tier.serializedName());
        json.addProperty("threshold", threshold);
        return json;
    }
}
