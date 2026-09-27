package com.cobbleraids.catching;

import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the trophy room screen is allowed to ask for: a page, under whatever search/filter/sort it
 * was requested with.
 *
 * <p>Read-only, unlike {@link com.cobbleraids.shop.RaidShopGateway} -- there is nothing to buy here.
 * The actual search/filter/sort/pagination rules live in {@link TrophyGalleryQuery}, which this class
 * only calls and translates to and from the wire format; that split is what lets the rules themselves
 * be unit-tested without a server.
 */
public final class TrophyRoomGateway {

    private TrophyRoomGateway() {}

    /** Opens the trophy room at its first page, with no search or filters applied yet. */
    public static void open(ServerPlayer player, int columns) {
        if (!ServerPlayNetworking.canSend(player, TrophyRoomPagePayload.TYPE)) {
            // Reachable only from an older client that predates this screen entirely -- the command
            // itself has no client-version check of its own, so this is the one place that can tell
            // the player why nothing happened instead of the request silently vanishing.
            player.sendSystemMessage(Component.literal(
                            "Your client doesn't support the trophy room yet -- update CobbleRaids.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        handle(player, TrophyRoomActionPayload.initial(columns));
    }

    /** Handles a page turn or a search/filter/sort change from the screen. */
    public static void handle(ServerPlayer player, TrophyRoomActionPayload action) {
        if (!ServerPlayNetworking.canSend(player, TrophyRoomPagePayload.TYPE)) return;

        TrophyGalleryQuery.Page page = TrophyGalleryQuery.select(
                TrophyLedger.forPlayer(player.getUUID()).values(),
                action.pageIndex(), action.columns(), action.query(), action.tier(),
                ordinal(TrophyGalleryQuery.Shiny.values(), action.shiny()),
                ordinal(TrophyGalleryQuery.Sort.values(), action.sort()));

        List<TrophyRoomEntryPayload> entries = new ArrayList<>(page.entries().size());
        for (TrophyEntry trophy : page.entries()) {
            entries.add(new TrophyRoomEntryPayload(
                    // Bare path, not "namespace:path" -- matches ShopSpeciesIcons/ShopPokemonPortraits'
                    // own lookup key, the same reason RaidShopGateway's personal page does this.
                    trophy.species().getPath(), trophy.level(), trophy.shiny(),
                    trophy.ivPercent(), trophy.evPercent(), trophy.rarityTier().serializedName(),
                    trophy.firstDefeatedAtEpochMs(), trophy.timesDefeated()));
        }
        ServerPlayNetworking.send(player, new TrophyRoomPagePayload(
                page.index(), page.count(), page.total(), page.shinyTotal(), page.filteredCount(), entries));
    }

    /** A raw network ordinal, clamped rather than trusted -- a stale or hostile client sends an int. */
    private static <T> T ordinal(T[] values, int raw) {
        return values[Math.floorMod(raw, values.length)];
    }
}
