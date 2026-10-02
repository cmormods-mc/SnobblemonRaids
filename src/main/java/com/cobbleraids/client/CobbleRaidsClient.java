package com.cobbleraids.client;

import com.cobbleraids.client.capture.CaptureDetailsCache;
import com.cobbleraids.client.capture.CaptureMinigameScreen;
import com.cobbleraids.client.capture.CaptureOfferScreen;
import com.cobbleraids.client.renown.RenownBoonClientCache;
import com.cobbleraids.client.reveal.RaidRewardRevealScreen;
import com.cobbleraids.client.shop.LegendRoomScreen;
import com.cobbleraids.client.shop.RaidShopScreen;
import com.cobbleraids.client.shop.TrophyRoomScreen;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.cobbleraids.network.LegendPagePayload;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RenownBoonSyncPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.cobbleraids.renown.RenownBoon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The only class in CobbleRaids that touches ClientPlayNetworking -- everything else stays common code. */
public final class CobbleRaidsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Guarded for the player's sake rather than the server's. These run inside the client's own
        // task queue, so an exception here is a crash screen and a lost session -- over a reward
        // reveal, which is cosmetic. The reward itself is server-side and already banked; a player
        // who never sees the screen can still claim with /cobbleraids reward.
        ClientPlayNetworking.registerGlobalReceiver(PendingRewardRevealPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("reveal-screen:open",
                        () -> RaidRewardRevealScreen.openFor(payload))));
        ClientPlayNetworking.registerGlobalReceiver(RewardResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("reveal-screen:result",
                        () -> RaidRewardRevealScreen.applyResult(payload))));
        ClientPlayNetworking.registerGlobalReceiver(ShopPagePayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("shop-screen:page",
                        () -> RaidShopScreen.show(payload))));
        ClientPlayNetworking.registerGlobalReceiver(TrophyRoomPagePayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("trophy-room:page",
                        () -> TrophyRoomScreen.show(payload))));
        ClientPlayNetworking.registerGlobalReceiver(LegendPagePayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("legend-room:page",
                        () -> LegendRoomScreen.show(payload))));
        ClientPlayNetworking.registerGlobalReceiver(CaptureDetailsPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("capture-screen:details",
                        () -> CaptureDetailsCache.remember(payload))));
        ClientPlayNetworking.registerGlobalReceiver(CaptureOfferPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("capture-screen:offer",
                        () -> CaptureOfferScreen.openFor(payload))));
        ClientPlayNetworking.registerGlobalReceiver(CapturePulseResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("capture-screen:pulse-result",
                        () -> CaptureMinigameScreen.applyPulseResult(payload))));
        ClientPlayNetworking.registerGlobalReceiver(CaptureResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("capture-screen:result",
                        () -> CaptureMinigameScreen.applyResult(payload))));
        ClientPlayNetworking.registerGlobalReceiver(RenownBoonSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidFaultBarrier.guard("renown-boon-sync", () ->
                        // Resent by the server once a second to every tracker, so this must stay silent.
                        RenownBoon.decode(payload.boonEncoded())
                                .ifPresent(boon -> RenownBoonClientCache.remember(payload.pokemonUuid(), boon)))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                RaidFaultBarrier.guard("capture-screen:disconnect-cleanup", CaptureDetailsCache::clear));

        RaidKeyBindings.register();
        // Polled once a tick. consumeClick drains every press since the last tick, so a key pressed
        // while a screen was open is spent here and never fires late when the screen closes.
        ClientTickEvents.END_CLIENT_TICK.register(client -> RaidFaultBarrier.guard("keybinds", () -> {
            while (RaidKeyBindings.trophy().consumeClick()) openTrophyRoom(client);
            while (RaidKeyBindings.leaderboard().consumeClick()) openLeaderboard(client);
        }));
    }

    /**
     * T. Asks the server for the first page, the same request /cobbleraids trophies makes; the screen
     * opens when the page arrives. Ignored with a screen already open -- the key may be a letter the
     * player is typing into a search box -- and told to the player when the server has no trophy room.
     */
    private static void openTrophyRoom(Minecraft client) {
        if (client.player == null || client.screen != null) return;
        if (!ClientPlayNetworking.canSend(TrophyRoomActionPayload.TYPE)) {
            client.player.displayClientMessage(
                    Component.literal("This server does not have the CobbleRaids trophy room."), true);
            return;
        }
        // The server cannot know the window's width yet; the screen asks again with its real column count.
        ClientPlayNetworking.send(TrophyRoomActionPayload.initial(com.cobbleraids.catching.TrophyGalleryQuery.MAX_COLUMNS));
    }

    /**
     * L. There is no leaderboard screen yet, so for now this prints the all-time raids-won board in
     * chat. Replace the body with the screen's open call when it exists; the key, the screen check
     * and the tick polling above are already what that needs.
     */
    private static void openLeaderboard(Minecraft client) {
        if (client.player == null || client.screen != null) return;
        client.player.connection.sendCommand("cobbleraids top raids_won alltime");
    }
}
