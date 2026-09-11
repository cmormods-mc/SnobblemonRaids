package com.cobbleraids.reward.currency;

import com.cobbleraids.RaidLog;
import net.fabricmc.loader.api.FabricLoader;

/** Selects and holds the single active currency backend for the server process. */
public final class RaidCurrencyBackends {

    private static volatile RaidCurrencyBackend active = new NoCurrencyBackend();

    private RaidCurrencyBackends() {}

    public static RaidCurrencyBackend active() {
        return active;
    }

    /**
     * Called once from SERVER_STARTING. Never throws: an economy mod that is present but does not
     * expose what this expects degrades to paying no currency, which leaves item rewards working
     * exactly as they do on a server with no economy mod at all.
     */
    public static void ensureReady() {
        if (!FabricLoader.getInstance().isModLoaded("cobbledollars")) {
            active = new NoCurrencyBackend();
            return;
        }
        CobbleDollarsCurrencyBackend backend = new CobbleDollarsCurrencyBackend();
        try {
            backend.ensureReady();
            active = backend;
        } catch (RuntimeException | LinkageError ex) {
            active = new NoCurrencyBackend();
            RaidLog.error("CobbleDollars is installed but its payout API could not be resolved ("
                    + ex.getMessage() + "); raids will grant items only.");
        }
    }
}
