package com.cobbleraids.reward;

import com.cobbleraids.CobbleRaids;
import com.pokeskies.skiesguis.SkiesGUIs;
import com.pokeskies.skiesguis.api.SkiesGUIsAPI;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Installs the safe default CobbleRaids reward GUI into SkiesGUIs without overwriting operator edits. */
public final class RaidRewardGuiInstaller {
    public static final String DEFAULT_GUI_ID = "cobbleraids_reward";
    private static final String RESOURCE = "/assets/cobbleraids/skiesguis/cobbleraids_reward.json";
    private RaidRewardGuiInstaller() {}

    public static void ensureInstalledAndLoaded() {
        Path target = FabricLoader.getInstance().getConfigDir()
                .resolve("skiesguis").resolve("guis").resolve(DEFAULT_GUI_ID + ".json");
        try {
            Files.createDirectories(target.getParent());
            boolean created = false;
            if (!Files.exists(target)) {
                try (InputStream in = CobbleRaids.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) throw new IllegalStateException("Missing bundled reward GUI resource " + RESOURCE);
                    Files.copy(in, target);
                    created = true;
                }
            }

            if (created || SkiesGUIsAPI.INSTANCE.getGUIConfig(DEFAULT_GUI_ID) == null) {
                if (SkiesGUIs.INSTANCE == null) throw new IllegalStateException("SkiesGUIs is not initialized despite required dependency");
                SkiesGUIs.INSTANCE.reload();
            }
            if (SkiesGUIsAPI.INSTANCE.getGUIConfig(DEFAULT_GUI_ID) == null)
                throw new IllegalStateException("SkiesGUIs did not load GUI id " + DEFAULT_GUI_ID + " from " + target);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to install/load CobbleRaids reward GUI at " + target, ex);
        }
    }
}
