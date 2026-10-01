package com.cobbleraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * The keys CobbleRaids adds to the controls screen.
 *
 * <p>Only the keys live here. What a press DOES is {@link CobbleRaidsClient}'s business, because that
 * is the one class allowed to touch client networking; keeping the two apart is what lets the
 * defaults be pinned by a test without a client.
 *
 * <p>The defaults are T and L by request, and both are vanilla's own: T opens chat and L opens the
 * advancements screen. Minecraft hands a pressed key to ONE of the bindings sharing it, so on a
 * default install a press of T or L may reach the vanilla action instead of ours. They are ordinary
 * rebindable keys; the controls screen shows a clash in red.
 */
public final class RaidKeyBindings {

    public static final int DEFAULT_TROPHY_KEY = GLFW.GLFW_KEY_T;
    public static final int DEFAULT_LEADERBOARD_KEY = GLFW.GLFW_KEY_L;

    /** Translation keys; the names in en_us.json are what the controls screen shows. */
    public static final String TROPHY_NAME = "key.cobbleraids.trophies";
    public static final String LEADERBOARD_NAME = "key.cobbleraids.leaderboard";
    public static final String CATEGORY = "key.categories.cobbleraids";

    private static KeyMapping trophy;
    private static KeyMapping leaderboard;

    private RaidKeyBindings() {}

    /** Registers both keys. Called once, from the client entrypoint. */
    public static void register() {
        trophy = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(TROPHY_NAME, InputConstants.Type.KEYSYM, DEFAULT_TROPHY_KEY, CATEGORY));
        leaderboard = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(LEADERBOARD_NAME, InputConstants.Type.KEYSYM, DEFAULT_LEADERBOARD_KEY, CATEGORY));
    }

    public static KeyMapping trophy() {
        return trophy;
    }

    public static KeyMapping leaderboard() {
        return leaderboard;
    }
}
