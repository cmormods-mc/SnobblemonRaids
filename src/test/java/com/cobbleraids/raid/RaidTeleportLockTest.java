package com.cobbleraids.raid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidTeleportLockTest {
    @Test
    void activeParticipantIsLockedWhileTheRaidRuns() {
        assertTrue(RaidTeleportLock.locks(RaidSession.Status.ACTIVE, false, true));
        assertTrue(RaidTeleportLock.locks(RaidSession.Status.WAITING, false, true));
    }

    @Test
    void withdrawnPlayerAndFinishedRaidAreFree() {
        assertFalse(RaidTeleportLock.locks(RaidSession.Status.ACTIVE, false, false));
        for (RaidSession.Status s : new RaidSession.Status[] {
                RaidSession.Status.COMPLETED, RaidSession.Status.FAILED, RaidSession.Status.ABORTED}) {
            assertFalse(RaidTeleportLock.locks(s, false, true), s.name());
        }
    }

    @Test
    void ownedEncountersMoveTheirOwnPlayers() {
        assertFalse(RaidTeleportLock.locks(RaidSession.Status.ACTIVE, true, true));
    }
}
