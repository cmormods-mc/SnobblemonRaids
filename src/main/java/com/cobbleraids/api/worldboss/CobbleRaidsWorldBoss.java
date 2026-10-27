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

    /** True when the World Boss system is switched on ({@code enabled} in {@code world_boss.json}); {@link #start} refuses otherwise. */
    public static boolean isEnabled() {
        return com.cobbleraids.worldboss.WorldBossConfigManager.get().enabled();
    }

    /**
     * Starts a World Boss now, the same way {@code /cr worldboss start} does: the event window and the night-only setting are not asked, but the
     * system must be enabled and no other World Boss may be active. With {@code spawn.auto_start} off in {@code world_boss.json} this is the
     * only way a boss appears besides the command, which lets another mod decide when the event happens. Call on the server thread.
     * Added after {@link #API_VERSION} 1 as a compatible addition.
     *
     * @param near a player to place the boss around (it must be in the configured spawn dimension, else a random player there is used), or null
     * @return null on success, otherwise a short reason the boss did not start
     */
    public static String start(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerPlayer near) {
        return WorldBossService.start(server, near);
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
