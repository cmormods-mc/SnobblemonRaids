package com.cobbleraids.placeholder;

import com.cobbleraids.CobbleRaids;
import com.cobbleraids.reward.points.RaidPointsStore;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code %cobbleraids:points%} -- the asking player's Raid Point balance, as a bare number.
 *
 * <p>Bare on purpose. A sidebar line is written by whoever owns the sidebar, and a token that
 * arrives pre-labelled or pre-coloured cannot be put in a line that wants a different label or a
 * different colour. {@code RAID POINTS: %cobbleraids:points%} is the caller's to write.
 *
 * <p>Every class the API needs is referenced here and nowhere else, so {@link RaidPlaceholders} can
 * decide whether the mod is present without loading any of it.
 */
final class RaidPointsToken {

    private RaidPointsToken() {}

    static void register() {
        Placeholders.register(
                ResourceLocation.fromNamespaceAndPath(CobbleRaids.MOD_ID, "points"),
                (context, argument) -> {
                    // Console, a command block, or a sidebar rendered for no one in particular:
                    // invalid leaves the raw token in place, which reads as a configuration
                    // mistake instead of quietly claiming everybody has nothing.
                    if (!context.hasPlayer()) {
                        return PlaceholderResult.invalid("No player");
                    }
                    return PlaceholderResult.value(
                            String.valueOf(RaidPointsStore.balance(context.player().getUUID())));
                });
    }
}
