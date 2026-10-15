package com.cobbleraids.network;

/**
 * One cell of the trophy room, as the web page needs to draw it.
 */
public record TrophyRoomEntryPayload(
        String species,
        int level,
        boolean shiny,
        int ivPercent,
        int evPercent,
        String tier,
        long firstDefeatedAtEpochMs,
        int timesDefeated
) {
}
