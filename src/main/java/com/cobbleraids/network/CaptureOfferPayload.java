package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: a Raid Capture Protocol choice is waiting -- attempt the minigame, or claim this raid's RP.
 * Sent only after the player has actually claimed this raid's reward (items already in hand,
 * unaffected); {@code raidPointsAtStake} is therefore the exact amount that specific claim computed
 * (renown and held-item multipliers already applied), not an estimate -- it is what capturing
 * forgoes, in full.
 *
 * <p>The six timing fields are this tier's {@code CaptureTierConfig} travel/zone widths, synced here
 * because the client has no other way to know them -- server config is never implicitly visible to
 * the client, and the minigame screen needs them to animate an accurate sweep and draw correctly
 * sized zones, not just to send inputs blind. The server alone still scores every input off its own
 * copy of these same numbers; nothing the client renders is trusted back.
 */
public record CaptureOfferPayload(
        UUID raidId,
        ResourceLocation definitionId,
        String tier,
        String speciesDisplayName,
        boolean shiny,
        int raidPointsAtStake,
        int choiceSecondsRemaining,
        int pulseTravelDurationMs,
        int pulseGoodZoneWidthMs,
        int pulsePerfectZoneWidthMs,
        int throwTravelDurationMs,
        int throwGoodZoneWidthMs,
        int throwPerfectZoneWidthMs
) {
}
