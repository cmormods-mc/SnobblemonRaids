package com.cobbleraids.network;

import java.util.UUID;

/**
 * S2C: the one and only capture roll, already decided and already persisted server-side before this
 * is ever sent. The client only ever animates toward this already-known result -- it never requests
 * an outcome after a local-only animation.
 *
 * <p>{@code delivery} mirrors {@code RaidCaptureSession.DeliveryState}'s name ({@code DELIVERED},
 * {@code PENDING_RETRY}, or {@code NONE} on the RP route, where nothing was ever rolled to deliver)
 * as a plain string rather than sharing the enum type, the same reason
 * {@link com.cobbleraids.catching.TrophyGalleryQuery}'s sort/tier fields travel as raw values instead
 * of a shared type: this channel and that server-side enum are free to change independently.
 */
public record CaptureResultPayload(
        UUID raidId,
        boolean success,
        double finalChancePercent,
        double stabilizationQualityPercent,
        double throwQualityPercent,
        String delivery,
        String speciesDisplayName,
        boolean shiny
) {
}
