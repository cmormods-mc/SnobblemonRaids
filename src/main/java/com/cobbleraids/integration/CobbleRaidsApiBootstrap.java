package com.cobbleraids.integration;

import com.cobbleraids.api.CobbleRaidsApi;
import net.fabricmc.api.ModInitializer;

/** Installs the stable addon-facing API after CobbleRaids' primary initializer is constructed. */
public final class CobbleRaidsApiBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        CobbleRaidsApi.installEncounterApi(new RaidEncounterApiImpl());
    }
}
