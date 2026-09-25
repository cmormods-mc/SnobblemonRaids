package com.cobbleraids.presentation;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.renown.RaidRenown;
import com.cobbleraids.renown.RenownBoon;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The one place a raid boss's name is built, for the nameplate, the spawn announcement and the
 * reward screen.
 *
 * <p>It exists because the name used to be built in two places. RaidLobbyManager rebuilt it from
 * the species name when dynamic level scaling raised a boss, so a renowned boss would have lost its
 * title at exactly the moment a party committed to fighting it.
 *
 * <p>A renowned boss reads "☠ Kaelen, the Relentless Gyarados ☠": the name gold and bold, the
 * epithet and skulls bold red, the species in its tier colour. Chosen from offline renders using
 * Minecraft's own font against sky, grass and cave backdrops. Dark red was ruled out there -- it
 * all but vanishes on grass and at night -- and the skull is a glyph from the game's bitmap
 * font (nonlatin_european.png), so it renders as crisply as the letters.
 */
public final class RaidBossNameplate {
    private static final String SKULL = "☠";

    private RaidBossNameplate() {}

    /**
     * @param scaledLevel the level a boss was raised to, or 0 when it fights at its definition level.
     *                    Cobblemon already labels the level, so it is only repeated once it changed.
     */
    public static MutableComponent of(RaidRarityTier tier, Component speciesName, RaidRenown renown, int scaledLevel) {
        Component species = scaledLevel > 0
                ? Component.literal(speciesName.getString() + " Lv. " + scaledLevel)
                : speciesName;
        if (renown == null) return RaidTierPresentation.styledName(tier, species);
        return Component.empty()
                .append(ornament(SKULL + " "))
                .append(Component.literal(renown.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(withBoonMarker(
                        Component.literal(", " + renown.epithet() + " ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        renown.boon()))
                .append(species.copy().withStyle(RaidTierPresentation.color(tier)))
                .append(ornament(" " + SKULL));
    }

    /**
     * Rides along on the entity's synced custom name so the client-side nameplate renderer can show
     * a boon icon without any packet of its own. {@code Style}'s chat-insertion field is otherwise
     * unused on a nameplate (there is nothing to shift-click here), so it is free to carry
     * {@link RenownBoon#marker()} instead -- piggybacked onto the epithet's own style rather than a
     * separate empty-text sibling, so there is no bare component anywhere in the tree whose fate
     * depends on how an empty string with an otherwise-plain style happens to (de)serialize.
     */
    private static MutableComponent withBoonMarker(MutableComponent epithet, RenownBoon boon) {
        return epithet.withStyle(style -> style.withInsertion(boon.marker()));
    }

    /** The inverse of {@link #boonMarker}: recovers the boon riding on an entity's display name, if any. */
    public static Optional<RenownBoon> readBoonMarker(Component displayName) {
        if (displayName == null) return Optional.empty();
        Optional<RenownBoon> here = RenownBoon.decodeMarker(displayName.getStyle().getInsertion());
        if (here.isPresent()) return here;
        for (Component sibling : displayName.getSiblings()) {
            Optional<RenownBoon> found = readBoonMarker(sibling);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** "☠ Kaelen, the Relentless ☠" on its own, for a surface that shows the species elsewhere. Null when blank. */
    public static MutableComponent banner(String renownTitle) {
        String[] parts = RaidRenown.splitTitle(renownTitle);
        if (parts[0] == null) return null;
        MutableComponent banner = Component.empty()
                .append(ornament(SKULL + " "))
                .append(Component.literal(parts[0]).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (parts[1] != null) {
            banner.append(Component.literal(", " + parts[1]).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        return banner.append(ornament(" " + SKULL));
    }

    private static MutableComponent ornament(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }
}
