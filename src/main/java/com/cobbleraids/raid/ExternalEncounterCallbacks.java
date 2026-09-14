package com.cobbleraids.raid;

import com.cobbleraids.api.RaidEncounterOutcome;
import com.cobbleraids.api.RaidEncounterResult;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.lifecycle.RaidOutcome;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Internal callback registry for addon-managed encounters. */
public final class ExternalEncounterCallbacks {
    private static final Map<UUID, Consumer<RaidEncounterResult>> CALLBACKS = new ConcurrentHashMap<>();

    private ExternalEncounterCallbacks() {}

    public static void bind(RaidSession raid, Consumer<RaidEncounterResult> callback) {
        Objects.requireNonNull(raid, "raid");
        Objects.requireNonNull(callback, "callback");
        if (!raid.isExternalEncounter()) {
            throw new IllegalArgumentException("Completion callbacks may only be bound to external encounters");
        }
        Consumer<RaidEncounterResult> previous = CALLBACKS.putIfAbsent(raid.getId(), callback);
        if (previous != null) throw new IllegalStateException("External encounter already has a completion callback");
    }

    /**
     * Removes before invoking so even a throwing callback cannot remain retained or fire twice.
     * Called only after CobbleRaids has completed mandatory battle/boss cleanup.
     */
    public static void complete(RaidSession raid) {
        if (raid == null || !raid.isExternalEncounter()) return;
        Consumer<RaidEncounterResult> callback = CALLBACKS.remove(raid.getId());
        if (callback == null) return;

        RaidEncounterResult result = new RaidEncounterResult(
                raid.getId(),
                raid.getBattle().getBattleId(),
                raid.getDefinitionId(),
                toPublicOutcome(raid.getOutcome()),
                raid.getParticipants(),
                raid.getActiveParticipants(),
                raid.getElapsedCombatTicks(),
                raid.getContributionSnapshot()
        );

        try {
            callback.accept(result);
        } catch (Exception ex) {
            RaidFaultBarrier.report("external-encounter-completion", ex);
        }
    }

    private static RaidEncounterOutcome toPublicOutcome(RaidOutcome outcome) {
        if (outcome == null) return RaidEncounterOutcome.ABORTED;
        return switch (outcome) {
            case VICTORY -> RaidEncounterOutcome.VICTORY;
            case DEFEAT -> RaidEncounterOutcome.DEFEAT;
            case ABORTED -> RaidEncounterOutcome.ABORTED;
        };
    }

    public static int onServerStopped() {
        int dropped = CALLBACKS.size();
        CALLBACKS.clear();
        return dropped;
    }
}
