package com.cobbleraids.client;

import com.cobbleraids.client.reveal.RaidRewardRevealScreen;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardResultPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The only class in CobbleRaids that touches ClientPlayNetworking -- everything else stays common code. */
public final class CobbleRaidsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PendingRewardRevealPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidRewardRevealScreen.openFor(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RewardResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RaidRewardRevealScreen.applyResult(payload)));
    }
}
