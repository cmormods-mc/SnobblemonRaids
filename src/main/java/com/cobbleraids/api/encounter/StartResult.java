package com.cobbleraids.api.encounter;

import java.util.Objects;
import java.util.UUID;

/** The answer to {@link CobbleRaidsEncounters#start}. */
public sealed interface StartResult permits StartResult.Started, StartResult.Refused {

    /** The battle is running. The listener will hear about it from here on. */
    record Started(UUID encounterId) implements StartResult {
        public Started {
            Objects.requireNonNull(encounterId, "encounterId");
        }
    }

    /** Nothing was started and nothing was left behind. {@code reason} is written for an operator. */
    record Refused(String reason) implements StartResult {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
