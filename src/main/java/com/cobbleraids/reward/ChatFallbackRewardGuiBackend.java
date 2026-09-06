package com.cobbleraids.reward;

import net.minecraft.server.level.ServerPlayer;

/** Used when SkiesGUIs is absent or failed to install; RaidRewardService sends choices via chat instead. */
final class ChatFallbackRewardGuiBackend implements RewardGuiBackend {
    @Override
    public void ensureReady() {}

    @Override
    public boolean open(ServerPlayer player, String guiId) {
        return false;
    }

    @Override
    public String name() {
        return "chat-fallback";
    }
}
