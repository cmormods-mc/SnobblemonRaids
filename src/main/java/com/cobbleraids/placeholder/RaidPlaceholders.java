package com.cobbleraids.placeholder;

import com.cobbleraids.RaidLog;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Publishes a Raid Point balance to whatever draws a scoreboard sidebar.
 *
 * <p>Text Placeholder API is the common token format the sidebar, chat and tab-list mods on this
 * pack all read, so registering one token there is cheaper than integrating with each of them --
 * and a server that has none of them simply never asks for it.
 *
 * <p>The API is optional, so nothing in this class mentions one of its types: the check below has
 * to complete before any class holding a {@code eu.pb4.placeholders} reference is linked, or a
 * server without the mod fails at class load rather than at the {@code if}. {@link RaidPointsToken}
 * is where those references live.
 */
public final class RaidPlaceholders {

    private static final String PLACEHOLDER_API = "placeholder-api";

    private RaidPlaceholders() {}

    /** Called once at initialisation. Never throws; a sidebar token is not worth a failed boot. */
    public static void registerIfPresent() {
        if (!FabricLoader.getInstance().isModLoaded(PLACEHOLDER_API)) {
            return;
        }
        try {
            RaidPointsToken.register();
        } catch (RuntimeException | LinkageError ex) {
            RaidLog.error("Text Placeholder API is installed but its registration API could not be"
                    + " resolved (" + ex.getMessage() + "); %cobbleraids:points% will not resolve.");
        }
    }
}
