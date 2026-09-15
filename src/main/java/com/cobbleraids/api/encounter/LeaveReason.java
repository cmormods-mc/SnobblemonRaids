package com.cobbleraids.api.encounter;

/** Why a participant left an encounter before it ended. */
public enum LeaveReason {
    /** The player's connection closed. */
    DISCONNECTED,
    /** The player used the raid's leave command. */
    WITHDREW,
    /** The player fled the battle. */
    FLED
}
