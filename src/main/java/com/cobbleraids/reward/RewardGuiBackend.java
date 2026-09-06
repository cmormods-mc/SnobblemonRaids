package com.cobbleraids.reward;

import net.minecraft.server.level.ServerPlayer;

/**
 * Isolates the optional SkiesGUIs dependency behind a small surface. Every other class in the mod
 * talks only to this interface (via RewardGuiBackends.active()) -- direct com.pokeskies.skiesguis.*
 * references live exclusively inside SkiesGuisRewardGuiBackend.
 */
public interface RewardGuiBackend {
    /** Runs once at SERVER_STARTING. No-op for backends with nothing to install. May throw. */
    void ensureReady();

    /** Attempts to present the reward choice interactively. False means: fall back to chat. */
    boolean open(ServerPlayer player, String guiId);

    /** Backend name surfaced via /cobbleraids debug status, e.g. "skiesguis" or "chat-fallback". */
    String name();
}
