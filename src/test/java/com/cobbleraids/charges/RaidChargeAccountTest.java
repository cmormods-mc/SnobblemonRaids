package com.cobbleraids.charges;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class RaidChargeAccountTest {
    private static final long NOW=1_800_000_000_000L,HOUR=3_600_000;
    @Test void newPlayersHaveThreeAndCannotBankExtraTimeAtFull() {
        assertEquals(RaidChargeAccount.FULL,RaidChargeAccount.FULL.at(NOW+100*HOUR));
        var spent=RaidChargeAccount.FULL.spend(NOW);
        assertEquals(2,spent.available());assertEquals(NOW+HOUR,spent.nextRefillAt());
    }
    @Test void poolRefillsExactlyOnePerHourUntilCapped() {
        var empty=RaidChargeAccount.FULL.spend(NOW).spend(NOW).spend(NOW);
        assertThrows(IllegalStateException.class,()->empty.spend(NOW));
        assertEquals(0,empty.at(NOW+HOUR-1).available());
        assertEquals(1,empty.at(NOW+HOUR).available());
        assertEquals(2,empty.at(NOW+2*HOUR).available());
        assertEquals(RaidChargeAccount.FULL,empty.at(NOW+3*HOUR));
        assertEquals(RaidChargeAccount.FULL,empty.at(NOW+500*HOUR));
    }
    @Test void repeatedSpendingKeepsTheExistingRefillDeadline() {
        var account=RaidChargeAccount.FULL.spend(NOW).spend(NOW+HOUR/2);
        assertEquals(NOW+HOUR,account.nextRefillAt());
        assertEquals(1,account.available());assertEquals(2,account.at(NOW+HOUR).available());
    }
    @Test void offlineTimeAndFractionalHourCarryForward() {
        var empty=RaidChargeAccount.FULL.spend(NOW).spend(NOW).spend(NOW);
        var resumed=empty.at(NOW+HOUR+HOUR/4);
        assertEquals(1,resumed.available());assertEquals(2700,resumed.secondsToNext(NOW+HOUR+HOUR/4));
        assertEquals(6300,resumed.secondsToFull(NOW+HOUR+HOUR/4));
        assertEquals(NOW+2*HOUR,resumed.spend(NOW+HOUR+HOUR/4).nextRefillAt());
    }
    @Test void spendingAfterFullStartsAFreshHour() {
        var spent=RaidChargeAccount.FULL.spend(NOW);
        assertEquals(NOW+6*HOUR,spent.spend(NOW+5*HOUR).nextRefillAt());
    }
    @Test void displayRoundsUpAndFullPoolHasNoCountdown() {
        var spent=RaidChargeAccount.FULL.spend(NOW);
        assertEquals(1,spent.secondsToNext(NOW+HOUR-1));
        assertEquals(0,spent.secondsToNext(NOW+HOUR));
        assertEquals(0,RaidChargeAccount.FULL.secondsToFull(NOW));
    }
    @Test void clockMovingBackCannotGrantCharges() {
        var empty=RaidChargeAccount.FULL.spend(NOW).spend(NOW).spend(NOW);
        assertEquals(0,empty.at(NOW-HOUR).available());
        assertEquals(NOW+HOUR,empty.at(NOW-HOUR).nextRefillAt());
    }
    @Test void invalidBalancesAndDeadlinesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new RaidChargeAccount(-1,NOW));
        assertThrows(IllegalArgumentException.class,()->new RaidChargeAccount(4,0));
        assertThrows(IllegalArgumentException.class,()->new RaidChargeAccount(0,0));
        assertThrows(IllegalArgumentException.class,()->new RaidChargeAccount(3,NOW));
    }
}
