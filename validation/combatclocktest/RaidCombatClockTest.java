import com.cobbleraids.lifecycle.RaidCombatClock;

public final class RaidCombatClockTest {
    public static void main(String[] args) {
        RaidCombatClock unlimited = new RaidCombatClock(0);
        if (unlimited.isTimed() || unlimited.getRemainingTicks() != -1) throw new AssertionError("0 seconds must mean unlimited");
        for (int i = 0; i < 100; i++) if (unlimited.tick()) throw new AssertionError("unlimited clock expired");

        RaidCombatClock oneSecond = new RaidCombatClock(1);
        if (!oneSecond.isTimed() || oneSecond.getLimitTicks() != 20) throw new AssertionError("1 second must be 20 ticks");
        for (int i = 1; i < 20; i++) {
            if (oneSecond.tick()) throw new AssertionError("expired early on tick " + i);
        }
        if (oneSecond.getRemainingTicks() != 1) throw new AssertionError("expected one tick remaining");
        if (!oneSecond.tick()) throw new AssertionError("did not expire exactly on tick 20");
        if (oneSecond.getRemainingTicks() != 0) throw new AssertionError("remaining ticks not zero");
        if (oneSecond.tick()) throw new AssertionError("expiry must be edge-triggered, not repeated");
        System.out.println("RaidCombatClockTest passed");
    }
}
