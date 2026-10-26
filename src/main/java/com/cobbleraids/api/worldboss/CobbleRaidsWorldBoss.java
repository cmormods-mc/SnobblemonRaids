package com.cobbleraids.api.worldboss;

import com.cobbleraids.worldboss.WorldBossService;
import java.util.UUID;

/**
 * The surface another mod uses to follow World Boss events (for example to pay its own reward).
 *
 * <p>Signatures use only {@code java.*}, {@code net.minecraft.*} and this package, the same rule as
 * {@code com.cobbleraids.api.encounter}. Experimental, {@link #API_VERSION} 1.
 */
public final class CobbleRaidsWorldBoss {

    /** Incremented on any incompatible change to this package. */
    public static final int API_VERSION = 1;

    private CobbleRaidsWorldBoss() {}

    /** Registers a listener for the life of the server. Registering the same instance twice is a no-op. */
    public static void addListener(WorldBossListener listener) {
        WorldBossService.addListener(listener);
    }

    /** Removes a listener added with {@link #addListener}. */
    public static void removeListener(WorldBossListener listener) {
        WorldBossService.removeListener(listener);
    }

    /** True while a World Boss is active. */
    public static boolean isActive() {
        return WorldBossService.isActive();
    }

    /** The active World Boss's id, or null when none is active. */
    public static UUID activeBossId() {
        return WorldBossService.activeBossId();
    }
}
