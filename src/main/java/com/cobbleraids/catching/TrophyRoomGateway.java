package com.cobbleraids.catching;

import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the trophy room screen is allowed to ask for: a page, and the next or previous one.
 *
 * <p>Read-only, unlike {@link com.cobbleraids.shop.RaidShopGateway} -- there is nothing to buy here,
 * only {@link TrophyLedger} entries to page through. Pages are built fresh per request rather than
 * cached, the same choice the shop makes, so a trophy earned while the screen happens to be open
 * shows up the moment the player turns back to that page.
 */
public final class TrophyRoomGateway {

    private TrophyRoomGateway() {}

    /** Entries per page; the same 4x4 Pokemon grid the shop's personal-boss page uses. */
    private static final int PAGE_SIZE = 16;

    /** Opens the trophy room at its first page. */
    public static void open(ServerPlayer player) {
        sendPage(player, 0);
    }

    /** Handles a page turn from the screen. */
    public static void handle(ServerPlayer player, TrophyRoomActionPayload action) {
        sendPage(player, action.pageIndex());
    }

    private static void sendPage(ServerPlayer player, int requestedPage) {
        List<TrophyEntry> sorted = new ArrayList<>(TrophyLedger.forPlayer(player.getUUID()).values());
        // By first defeat, oldest first: a trophy room reads as a timeline of a player's raiding
        // history, not a list that reshuffles between one open and the next.
        sorted.sort(Comparator.comparingLong(TrophyEntry::firstDefeatedAtEpochMs));

        int pageCount = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int index = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int from = Math.min(index * PAGE_SIZE, sorted.size());
        int to = Math.min(from + PAGE_SIZE, sorted.size());

        List<TrophyRoomEntryPayload> entries = new ArrayList<>(to - from);
        for (TrophyEntry trophy : sorted.subList(from, to)) {
            entries.add(new TrophyRoomEntryPayload(
                    // Bare path, not "namespace:path" -- matches ShopSpeciesIcons/ShopPokemonPortraits'
                    // own lookup key, the same reason RaidShopGateway's personal page does this.
                    bareSpecies(trophy.species()), trophy.level(), trophy.shiny(),
                    trophy.ivPercent(), trophy.evPercent(), trophy.rarityTier().serializedName(),
                    trophy.firstDefeatedAtEpochMs(), trophy.timesDefeated()));
        }
        ServerPlayNetworking.send(player, new TrophyRoomPagePayload(index, pageCount, entries));
    }

    private static String bareSpecies(ResourceLocation species) {
        return species.getPath();
    }
}
