package com.cobbleraids.reward;

import com.pokeskies.skiesguis.api.SkiesGUIsAPI;
import net.minecraft.server.level.ServerPlayer;

/** The only class that touches com.pokeskies.skiesguis.* -- only instantiated when SkiesGUIs is loaded. */
final class SkiesGuisRewardGuiBackend implements RewardGuiBackend {
    @Override
    public void ensureReady() {
        RaidRewardGuiInstaller.ensureInstalledAndLoaded();
    }

    @Override
    public boolean open(ServerPlayer player, String guiId) {
        SkiesGUIsAPI.INSTANCE.attemptGUIOpen(player, guiId);
        return true;
    }

    @Override
    public String name() {
        return "skiesguis";
    }
}
