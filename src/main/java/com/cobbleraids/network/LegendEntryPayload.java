package com.cobbleraids.network;

import java.util.List;
import java.util.UUID;

/**
 * One row of the Hall of Legends, as the web page needs to draw it.
 *
 * <p>{@code victorIds}/{@code victorNames} are bounded at {@code CobbleRaidsConfig.
 * VALIDATED_MAX_HUMAN_PLAYERS} (4) by construction on the server side -- see {@link LegendPagePayload}.
 */
public record LegendEntryPayload(
        String title,
        String species,
        String tier,
        String boon,
        long defeatedAtEpochMs,
        List<UUID> victorIds,
        List<String> victorNames
) {
}
