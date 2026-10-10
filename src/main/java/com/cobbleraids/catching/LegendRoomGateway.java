package com.cobbleraids.catching;

import com.cobbleraids.network.LegendActionPayload;
import com.cobbleraids.network.LegendEntryPayload;
import com.cobbleraids.network.LegendPagePayload;
import com.cobbleraids.network.RequestThrottle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the Hall of Legends screen is allowed to ask for: a page, under whatever "mine"/tier filter
 * it was requested with.
 *
 * <p>Read-only, same reasoning as {@link TrophyRoomGateway}. The filter/pagination rules live in
 * {@link LegendGalleryQuery}, which this class only calls and translates to and from the wire format.
 */
public final class LegendRoomGateway {

    private LegendRoomGateway() {}

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

    /** Opens the Hall of Legends at its first page, browsing every legend, no filter applied yet. */
    public static void open(ServerPlayer player, int columns) {
        if (!ServerPlayNetworking.canSend(player, LegendPagePayload.TYPE)) {
            player.sendSystemMessage(Component.literal(
                            "Your client doesn't support the Hall of Legends yet -- update CobbleRaids.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        handle(player, LegendActionPayload.initial(columns));
    }

    /** Handles a page turn or a "mine"/tier filter change from the screen. */
    public static void handle(ServerPlayer player, LegendActionPayload action) {
        if (!ServerPlayNetworking.canSend(player, LegendPagePayload.TYPE)) return;
        if (!THROTTLE.allow(player.getUUID(), System.currentTimeMillis())) return;

        LegendGalleryQuery.Page page = LegendGalleryQuery.select(HallOfLegends.all(), action.pageIndex(),
                action.columns(), player.getUUID(), action.mineOnly(), action.tier());

        List<LegendEntryPayload> entries = new ArrayList<>(page.entries().size());
        for (LegendEntry legend : page.entries()) {
            List<UUID> victorIds = new ArrayList<>(legend.victorIds());
            List<String> victorNames = new ArrayList<>(legend.victorNames());
            entries.add(new LegendEntryPayload(legend.title(), legend.species().getPath(),
                    legend.tier().serializedName(), legend.boon().encode(), legend.defeatedAtEpochMs(),
                    victorIds, victorNames));
        }
        ServerPlayNetworking.send(player, new LegendPagePayload(
                page.index(), page.count(), page.total(), page.mineCount(), entries));
    }
}
