package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * One player's Raid Capture Protocol attempt for one raid, from the moment they are offered the
 * choice through however it eventually settles.
 *
 * <p>The species/form/level/shiny snapshot is read once, at victory, from the boss's own live
 * Pokemon -- the entity and its Pokemon are both discarded before this session could ever be acted
 * on again, so nothing downstream can re-read them. The session then waits in
 * {@link CapturePhase#AWAITING_CLAIM} until the player actually claims that raid's reward (items are
 * granted then, completely unaffected) -- only at that moment does this raid's Raid Points get
 * computed, withheld from the normal credit, and banked here as {@code bankedRaidPoints}, and only
 * then is the player shown the capture choice at all. This is deliberate: the reward summary/reveal
 * screen must behave exactly as it always has, and the capture prompt is a thing that happens next,
 * not a thing that gates or replaces it.
 *
 * <p>{@code currentStepStartedAtEpochMs} is when the minigame step currently in progress (a pulse's
 * or the throw's travel window) began; a pulse or throw is scored from how far the server's own
 * receipt of that input packet falls from that step's zone center, never from a client-reported
 * timestamp -- see {@link RaidCaptureSessionService}. {@code sequenceDeadlineEpochMs} covers the
 * whole stabilize-then-throw span as one deadline, not one per pulse, so "the player went AFK
 * mid-sequence" and "the player never started at all" resolve through the same zero-fill path.
 *
 * <p>{@code pokemonNbt} is populated only once the roll succeeds, at the instant it succeeds --
 * never re-rolled or rebuilt on a later delivery retry, the same discipline
 * {@link BossSnapshot#pokemonNbt()} follows for the unrelated Personal Boss Shop buy-back.
 */
public record RaidCaptureSession(
        UUID playerId,
        UUID raidId,
        ResourceLocation definitionId,
        RaidRarityTier tier,
        ResourceLocation species,
        String form,
        int level,
        boolean shiny,
        CapturePhase phase,
        int bankedRaidPoints,
        long choiceDeadlineEpochMs,
        long currentStepStartedAtEpochMs,
        long sequenceDeadlineEpochMs,
        double pulse1Score,
        double pulse2Score,
        double pulse3Score,
        int nextPulseIndex,
        double throwScore,
        boolean rolled,
        boolean rollSucceeded,
        double finalChanceUsed,
        DeliveryState delivery,
        CompoundTag pokemonNbt
) {
    public enum CapturePhase { AWAITING_CLAIM, AWAITING_CHOICE, STABILIZING, AWAITING_THROW, RESOLVED }

    /** Only meaningful once {@code rolled && rollSucceeded}; every other outcome never sets it. */
    public enum DeliveryState { NONE, PENDING_RETRY, DELIVERED }

    /**
     * Created at victory, before the player has claimed anything. {@code bankedRaidPoints} is 0 and
     * meaningless until {@link #withClaimed} runs; there is deliberately no deadline on
     * {@code AWAITING_CLAIM} itself -- it waits exactly as long as the reward queue itself would, see
     * {@code RaidRewardService}, since claiming can legitimately happen long after victory.
     */
    public static RaidCaptureSession offer(UUID playerId, UUID raidId, ResourceLocation definitionId,
                                           RaidRarityTier tier, ResourceLocation species, String form,
                                           int level, boolean shiny) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                CapturePhase.AWAITING_CLAIM, 0, 0L, 0L, 0L,
                0.0, 0.0, 0.0, 0, 0.0, false, false, 0.0, DeliveryState.NONE, null);
    }

    /**
     * The moment the player actually claims this raid's reward: items are already in hand by now
     * (unaffected), and this raid's computed Raid Points -- withheld from the normal credit -- are
     * banked here instead. Only now does the choice window actually start.
     */
    public RaidCaptureSession withClaimed(int raidPoints, long nowEpochMs, int choiceWindowSeconds) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                CapturePhase.AWAITING_CHOICE, raidPoints, nowEpochMs + choiceWindowSeconds * 1000L,
                currentStepStartedAtEpochMs, sequenceDeadlineEpochMs,
                pulse1Score, pulse2Score, pulse3Score, nextPulseIndex, throwScore, rolled, rollSucceeded,
                finalChanceUsed, delivery, pokemonNbt);
    }

    /**
     * Resolves straight to RP without ever rolling -- a player who declined outright, or whose choice
     * window lapsed before they acted. Distinct from an attempt that rolled and lost ({@code rolled}
     * stays false here), so a later stat pass can tell "declined" apart from "tried and lost".
     */
    public RaidCaptureSession withDeclined() {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                CapturePhase.RESOLVED, bankedRaidPoints, choiceDeadlineEpochMs, currentStepStartedAtEpochMs,
                sequenceDeadlineEpochMs, pulse1Score, pulse2Score, pulse3Score, nextPulseIndex, throwScore,
                rolled, rollSucceeded, finalChanceUsed, delivery, pokemonNbt);
    }

    /** Leaves AWAITING_CHOICE and starts the first pulse's travel window now. */
    public RaidCaptureSession withAttemptStarted(long nowEpochMs, int sequenceTimeoutSeconds) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                CapturePhase.STABILIZING, bankedRaidPoints, choiceDeadlineEpochMs, nowEpochMs,
                nowEpochMs + sequenceTimeoutSeconds * 1000L,
                0.0, 0.0, 0.0, 0, 0.0, false, false, 0.0, delivery, pokemonNbt);
    }

    /**
     * Records one pulse's score at {@link #nextPulseIndex()} and either starts the next pulse's
     * travel window now, or -- once the third has been scored -- moves on to the throw's.
     */
    public RaidCaptureSession withPulseScored(double score, long nowEpochMs) {
        double p1 = nextPulseIndex == 0 ? score : pulse1Score;
        double p2 = nextPulseIndex == 1 ? score : pulse2Score;
        double p3 = nextPulseIndex == 2 ? score : pulse3Score;
        int nextIndex = nextPulseIndex + 1;
        CapturePhase newPhase = nextIndex >= 3 ? CapturePhase.AWAITING_THROW : CapturePhase.STABILIZING;
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                newPhase, bankedRaidPoints, choiceDeadlineEpochMs, nowEpochMs, sequenceDeadlineEpochMs,
                p1, p2, p3, nextIndex, throwScore, rolled, rollSucceeded, finalChanceUsed, delivery,
                pokemonNbt);
    }

    /** The one and only capture roll for this session. Never called twice. */
    public RaidCaptureSession withThrowResolved(double throwScoreValue, boolean succeeded, double chanceUsed) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                CapturePhase.RESOLVED, bankedRaidPoints, choiceDeadlineEpochMs, currentStepStartedAtEpochMs,
                sequenceDeadlineEpochMs, pulse1Score, pulse2Score, pulse3Score, nextPulseIndex, throwScoreValue,
                true, succeeded, chanceUsed, delivery, pokemonNbt);
    }

    public RaidCaptureSession withPokemonNbt(CompoundTag nbt) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                phase, bankedRaidPoints, choiceDeadlineEpochMs, currentStepStartedAtEpochMs, sequenceDeadlineEpochMs,
                pulse1Score, pulse2Score, pulse3Score, nextPulseIndex, throwScore, rolled, rollSucceeded,
                finalChanceUsed, delivery, nbt);
    }

    public RaidCaptureSession withDelivery(DeliveryState newDelivery) {
        return new RaidCaptureSession(playerId, raidId, definitionId, tier, species, form, level, shiny,
                phase, bankedRaidPoints, choiceDeadlineEpochMs, currentStepStartedAtEpochMs, sequenceDeadlineEpochMs,
                pulse1Score, pulse2Score, pulse3Score, nextPulseIndex, throwScore, rolled, rollSucceeded,
                finalChanceUsed, newDelivery, pokemonNbt);
    }

    public double stabilizationQuality() {
        return RaidCaptureMath.stabilizationQuality(pulse1Score, pulse2Score, pulse3Score);
    }

    /**
     * Whether this session is due to be auto-resolved as of {@code nowEpochMs} -- the same check
     * whether the player is online or not, since a disconnect changes nothing about a wall-clock
     * deadline. {@code AWAITING_CLAIM} never expires on its own (it waits on the claim, not a clock)
     * and {@code RESOLVED} has nothing left to expire toward.
     */
    public boolean isExpired(long nowEpochMs) {
        return switch (phase) {
            case AWAITING_CLAIM -> false;
            case AWAITING_CHOICE -> nowEpochMs >= choiceDeadlineEpochMs;
            case STABILIZING, AWAITING_THROW -> nowEpochMs >= sequenceDeadlineEpochMs;
            case RESOLVED -> false;
        };
    }
}
