package com.cobbleraids.api.encounter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How an owned encounter ended.
 *
 * @param encounterId           the caller's id from the request
 * @param outcome               victory, defeat or abort
 * @param contribution          damage dealt to the shared pool per player, including players who left
 * @param remainingParticipants players still in the battle when it ended
 * @param elapsedCombatTicks    how long the battle ran
 */
public record EncounterResult(
        UUID encounterId,
        EncounterOutcome outcome,
        Map<UUID, Float> contribution,
        Set<UUID> remainingParticipants,
        int elapsedCombatTicks) {

    public EncounterResult {
        contribution = Map.copyOf(contribution);
        remainingParticipants = Set.copyOf(remainingParticipants);
    }
}
