package com.cobbleraids.reward;

import net.fabricmc.loader.api.FabricLoader;

/** Selects and holds the single active reward-GUI backend for the server process. */
public final class RewardGuiBackends {
    private static volatile RewardGuiBackend active = FabricLoader.getInstance().isModLoaded("skiesguis")
            ? new SkiesGuisRewardGuiBackend()
            : new ChatFallbackRewardGuiBackend();

    private RewardGuiBackends() {}

    public static RewardGuiBackend active() {
        return active;
    }

    /**
     * Called once from SERVER_STARTING. Never throws: a SkiesGUIs install/load failure degrades to
     * the chat fallback instead of aborting server start.
     */
    public static void ensureReady() {
        try {
            active.ensureReady();
        } catch (RuntimeException ex) {
            System.err.println("[CobbleRaids] SkiesGUIs reward GUI unavailable (" + ex.getMessage()
                    + "); falling back to chat-based reward claiming. Players use /cobbleraids reward to claim.");
            active = new ChatFallbackRewardGuiBackend();
        }
    }
}
