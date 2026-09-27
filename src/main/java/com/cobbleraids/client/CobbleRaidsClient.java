package com.cobbleraids.client;

import com.cobbleraids.RaidLog;
import com.cobbleraids.client.capture.CaptureMinigameScreen;
import com.cobbleraids.client.capture.CaptureOfferScreen;
import com.cobbleraids.client.renown.RenownBoonClientCache;
import com.cobbleraids.client.reveal.RaidRewardRevealScreen;
import com.cobbleraids.client.shop.RaidShopScreen;
import com.cobbleraids.client.shop.TrophyRoomScreen;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RenownBoonSyncPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.cobbleraids.renown.RenownBoon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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
                context.client().execute(() -> RaidFaultBarrier.guard("renown-boon-sync", () -> {
                    // TEMPORARY diagnostic while the boon-icon feature is being live-tested.
                    java.util.Optional<RenownBoon> decoded = RenownBoon.decode(payload.boonEncoded());
                    RaidLog.info("[boon-sync] client received pokemonUuid={} boonEncoded={} decoded={}",
                            payload.pokemonUuid(), payload.boonEncoded(), decoded.isPresent());
                    decoded.ifPresent(boon -> RenownBoonClientCache.remember(payload.pokemonUuid(), boon));
                })));
    }
}
