package com.cobbleraids.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable terminal snapshot emitted after an addon-managed encounter has been cleaned up. */
public record RaidEncounterResult(
        UUID encounterId,
        UUID battleId,
        ResourceLocation definitionId,
        RaidEncounterOutcome outcome,
        Set<UUID> participants,
        Set<UUID> activeParticipants,
        int elapsedCombatTicks,
        Map<UUID, Float> contribution
) {
    public RaidEncounterResult {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(outcome, "outcome");
        participants = Set.copyOf(participants);
        activeParticipants = Set.copyOf(activeParticipants);
        contribution = Map.copyOf(contribution);
        if (elapsedCombatTicks < 0) throw new IllegalArgumentException("elapsedCombatTicks must be >= 0");
        if (!participants.containsAll(activeParticipants))
            throw new IllegalArgumentException("activeParticipants must be a subset of participants");
    }
}
