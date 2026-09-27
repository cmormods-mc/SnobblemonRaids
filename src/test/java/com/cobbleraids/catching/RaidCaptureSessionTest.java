package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure state-machine transitions, with no server or SavedData involved. */
class RaidCaptureSessionTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.parse("cobblemon:garchomp");

    private static RaidCaptureSession offered() {
        return RaidCaptureSession.offer(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobbleraids:test_raid"), RaidRarityTier.LEGENDARY,
                GARCHOMP, null, 75, true);
    }

    private static RaidCaptureSession claimed(long nowEpochMs, int raidPoints) {
        return offered().withClaimed(raidPoints, nowEpochMs, 60);
    }

    @Test
    @DisplayName("offer starts AWAITING_CLAIM, with nothing banked and no deadline running yet")
    void offerStartsAwaitingClaim() {
        RaidCaptureSession session = offered();

        assertEquals(RaidCaptureSession.CapturePhase.AWAITING_CLAIM, session.phase());
        assertEquals(0, session.bankedRaidPoints());
        assertEquals(0, session.nextPulseIndex());
        assertFalse(session.rolled());
        assertNull(session.pokemonNbt());
        assertFalse(session.isExpired(999_999_999L), "AWAITING_CLAIM waits on the claim, not a clock");
    }

    @Test
    @DisplayName("claiming banks the points and starts the choice deadline, only now")
    void claimingBanksPointsAndStartsDeadline() {
        RaidCaptureSession session = claimed(1_000_000L, 76);

        assertEquals(RaidCaptureSession.CapturePhase.AWAITING_CHOICE, session.phase());
        assertEquals(76, session.bankedRaidPoints());
        assertEquals(1_000_000L + 60_000L, session.choiceDeadlineEpochMs());
    }

    @Test
    @DisplayName("declining resolves without ever rolling, and keeps the banked points intact")
    void decliningNeverRolls() {
        RaidCaptureSession declined = claimed(1_000_000L, 76).withDeclined();

        assertEquals(RaidCaptureSession.CapturePhase.RESOLVED, declined.phase());
        assertFalse(declined.rolled(), "a decline is not an attempt that lost");
        assertFalse(declined.rollSucceeded());
        assertEquals(76, declined.bankedRaidPoints(), "declining must not lose the figure it would credit");
    }

    @Test
    @DisplayName("attempting starts the first pulse's travel window and sets the sequence deadline")
    void attemptingStartsStabilizing() {
        RaidCaptureSession started = claimed(1_000_000L, 76).withAttemptStarted(1_005_000L, 18);

        assertEquals(RaidCaptureSession.CapturePhase.STABILIZING, started.phase());
        assertEquals(1_005_000L, started.currentStepStartedAtEpochMs());
        assertEquals(1_005_000L + 18_000L, started.sequenceDeadlineEpochMs());
        assertEquals(0, started.nextPulseIndex());
        assertEquals(76, started.bankedRaidPoints(), "starting an attempt must not disturb the banked points");
    }

    @Test
    @DisplayName("each pulse lands in its own slot, in order, and advances the index")
    void pulsesLandInOrder() {
        RaidCaptureSession session = claimed(0L, 76).withAttemptStarted(0L, 18);

        session = session.withPulseScored(1.0, 100L);
        assertEquals(1.0, session.pulse1Score(), 0.0);
        assertEquals(0.0, session.pulse2Score(), 0.0);
        assertEquals(1, session.nextPulseIndex());
        assertEquals(RaidCaptureSession.CapturePhase.STABILIZING, session.phase());
        assertEquals(100L, session.currentStepStartedAtEpochMs(), "each pulse restarts the step clock");

        session = session.withPulseScored(0.5, 200L);
        assertEquals(0.5, session.pulse2Score(), 0.0);
        assertEquals(2, session.nextPulseIndex());
        assertEquals(RaidCaptureSession.CapturePhase.STABILIZING, session.phase());

        session = session.withPulseScored(0.0, 300L);
        assertEquals(0.0, session.pulse3Score(), 0.0);
        assertEquals(3, session.nextPulseIndex());
        assertEquals(RaidCaptureSession.CapturePhase.AWAITING_THROW, session.phase(),
                "the third pulse moves straight into the throw");
    }

    @Test
    @DisplayName("stabilizationQuality is the mean of all three pulses regardless of order scored")
    void stabilizationQualityIsTheMean() {
        RaidCaptureSession session = claimed(0L, 76).withAttemptStarted(0L, 18)
                .withPulseScored(1.0, 0L).withPulseScored(0.5, 0L).withPulseScored(0.0, 0L);

        assertEquals(0.5, session.stabilizationQuality(), 1.0e-9);
    }

    @Test
    @DisplayName("the throw resolution is the one and only roll, and it is never repeatable")
    void throwResolvesExactlyOnce() {
        RaidCaptureSession session = claimed(0L, 76).withAttemptStarted(0L, 18)
                .withPulseScored(1.0, 0L).withPulseScored(1.0, 0L).withPulseScored(1.0, 0L);

        RaidCaptureSession resolved = session.withThrowResolved(1.0, true, 0.12);

        assertEquals(RaidCaptureSession.CapturePhase.RESOLVED, resolved.phase());
        assertTrue(resolved.rolled());
        assertTrue(resolved.rollSucceeded());
        assertEquals(0.12, resolved.finalChanceUsed(), 0.0);
        assertEquals(1.0, resolved.throwScore(), 0.0);
    }

    @Test
    @DisplayName("before-choice: a claimed session past its choice deadline is expired, online or not")
    void awaitingChoicePastDeadlineIsExpired() {
        RaidCaptureSession session = claimed(1_000_000L, 76); // choiceDeadlineEpochMs = 1_060_000L

        assertFalse(session.isExpired(1_059_999L), "not yet due");
        assertTrue(session.isExpired(1_060_000L), "exactly at the deadline counts as due");
        assertTrue(session.isExpired(9_999_999L), "long overdue, e.g. the player never reconnected");
    }

    @Test
    @DisplayName("mid-minigame: a session past its sequence deadline is expired regardless of progress")
    void midSequencePastDeadlineIsExpired() {
        RaidCaptureSession freshAttempt = claimed(0L, 76).withAttemptStarted(0L, 18); // deadline = 18_000L
        assertFalse(freshAttempt.isExpired(17_999L));
        assertTrue(freshAttempt.isExpired(18_000L));

        // Disconnecting after landing one pulse must not reset or extend the original deadline --
        // the same wall-clock instant governs the whole stabilize-then-throw span.
        RaidCaptureSession onePulseIn = freshAttempt.withPulseScored(1.0, 5_000L);
        assertFalse(onePulseIn.isExpired(17_999L));
        assertTrue(onePulseIn.isExpired(18_000L));
    }

    @Test
    @DisplayName("a resolved session is never expired -- it has nothing left to expire toward")
    void resolvedSessionIsNeverExpired() {
        RaidCaptureSession resolved = claimed(0L, 76).withDeclined();

        assertFalse(resolved.isExpired(0L));
        assertFalse(resolved.isExpired(999_999_999L));
    }

    @Test
    @DisplayName("withers only change the field they name, nothing else")
    void withersAreScopedToTheirOwnField() {
        RaidCaptureSession session = claimed(0L, 76);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("marker", "test");

        RaidCaptureSession withNbt = session.withPokemonNbt(nbt);
        assertEquals(nbt, withNbt.pokemonNbt());
        assertEquals(session.phase(), withNbt.phase());
        assertEquals(76, withNbt.bankedRaidPoints(), "attaching NBT must not disturb the banked points");

        RaidCaptureSession withDelivery = withNbt.withDelivery(RaidCaptureSession.DeliveryState.PENDING_RETRY);
        assertEquals(RaidCaptureSession.DeliveryState.PENDING_RETRY, withDelivery.delivery());
        assertEquals(nbt, withDelivery.pokemonNbt(), "marking delivery must not disturb the stored NBT");
    }
}
