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

    @Test
    void aCorrectionToTheCurrentPositionIsNotAWarp() {
        assertTrue(RaidTeleportLock.isInPlace(10, 64, -3, 10, 64, -3, false, false, false));
        assertTrue(RaidTeleportLock.isInPlace(10, 64, -3, 0, 0, 0, true, true, true));
    }

    @Test
    void anyOtherDestinationIsAWarp() {
        assertFalse(RaidTeleportLock.isInPlace(10, 64, -3, 400, 64, 400, false, false, false));
        assertFalse(RaidTeleportLock.isInPlace(10, 64, -3, 0, 5, 0, true, true, true));
        assertFalse(RaidTeleportLock.isInPlace(10, 64, -3, 10, 64, -2, false, false, false));
    }
}
