package com.cobbleraids.catching;

import com.cobbleraids.network.RequestThrottle;
import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.cobbleraids.web.TrophyWebPage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /**
     * A flood guard on the request packets, not pacing: each one filters and sorts a player's whole
     * ledger on the server thread, and nothing but a hostile client sends this many. The screen
     * itself sends one request at a time and cannot recover from a dropped one, so the budget sits
     * far above what a person can click.
     */
    private static final RequestThrottle THROTTLE = new RequestThrottle(20, 1000);

    /** Forgets a disconnected player's request budget. */
    public static void forget(UUID player) {
        THROTTLE.forget(player);
    }

    /** Opens the trophy room at its first page, with no search or filters applied yet. */
    public static void open(ServerPlayer player, int columns) {
        if (!TrophyWebPage.open(player)) {
            player.sendSystemMessage(Component.literal(
                            "The trophy room needs the WebGUI mod on your client.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** Handles a page turn or a search/filter/sort change from the web page. */
    public static void handle(ServerPlayer player, TrophyRoomActionPayload action) {
        if (!THROTTLE.allow(player.getUUID(), System.currentTimeMillis())) return;

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
        TrophyRoomPagePayload reply = new TrophyRoomPagePayload(
                page.index(), page.count(), page.total(), page.shinyTotal(), page.filteredCount(), entries);
        TrophyWebPage.deliver(player, reply);
    }

    /** A raw network ordinal, clamped rather than trusted -- a stale or hostile client sends an int. */
    private static <T> T ordinal(T[] values, int raw) {
        return values[Math.floorMod(raw, values.length)];
    }
}
