package com.cobbleraids.api;

import java.util.Objects;

/** Stable entry point for addon-facing CobbleRaids services. */
public final class CobbleRaidsApi {
    private static volatile RaidEncounterApi encounters;

    private CobbleRaidsApi() {}

    public static RaidEncounterApi encounters() {
        RaidEncounterApi api = encounters;
        if (api == null) throw new IllegalStateException("CobbleRaids encounter API is not initialized yet");
        return api;
    }

    /**
     * Bootstrap hook used by CobbleRaids itself. The binding is intentionally one-shot so another
     * mod cannot silently replace the implementation after startup.
     */
    public static synchronized void installEncounterApi(RaidEncounterApi api) {
        Objects.requireNonNull(api, "api");
        if (encounters != null) throw new IllegalStateException("CobbleRaids encounter API already initialized");
        encounters = api;
    }
}
