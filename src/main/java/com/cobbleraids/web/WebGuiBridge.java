package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.perf.RaidPerf;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The only class that talks to WebGUI, and it does so by name rather than by type.
 *
 * <p>WebGUI's jar is built with a newer Loom than this project uses, so Gradle cannot remap it as a
 * compile dependency. Calling it through method handles also keeps it a genuinely optional mod: no
 * class of ours links against a type that may be absent. The cost is that a change to WebGUI's API
 * is not a compile error, so {@link #bind()} resolves every call once at startup and reports
 * exactly which one is missing, instead of letting the first player to open a page find out.
 *
 * <p>These names are WebGUI's public {@code land.webgui.api.WebviewApi} (and, for the asset rescan,
 * {@code land.webgui.server.WebviewAssets}). At runtime both mods are in the same namespace, so a
 * {@link ServerPlayer} here is the very class WebGUI's methods take.
 */
final class WebGuiBridge {
    private WebGuiBridge() {}

    private static final String API = "land.webgui.api.WebviewApi";
    private static final String ASSETS = "land.webgui.server.WebviewAssets";
    private static final ResourceLocation OPEN_CHANNEL = ResourceLocation.fromNamespaceAndPath("webgui", "open_web");

    private static MethodHandle openGui;
    private static MethodHandle emitToPage;
    private static MethodHandle onPageEvent;
    private static MethodHandle reloadAssets;
    private static MethodHandle sendMainMenu;
    private static MethodHandle configuredMainMenu;
    private static boolean bound;

    /** Resolves every WebGUI call this mod makes. Returns false, and says why, if any is missing. */
    static boolean bind() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> api = Class.forName(API);
            Class<?> assets = Class.forName(ASSETS);
            openGui = lookup.findStatic(api, "openGui",
                    MethodType.methodType(void.class, ServerPlayer.class, String.class));
            emitToPage = lookup.findStatic(api, "emitToPage",
                    MethodType.methodType(void.class, ServerPlayer.class, String.class, String.class));
            onPageEvent = lookup.findStatic(api, "onPageEvent",
                    MethodType.methodType(void.class, String.class, BiConsumer.class));
            reloadAssets = lookup.findStatic(assets, "reload", MethodType.methodType(void.class));
            sendMainMenu = lookup.findStatic(api, "sendMainMenuUrl",
                    MethodType.methodType(void.class, ServerPlayer.class, String.class));
            configuredMainMenu = lookup.findStatic(Class.forName("land.webgui.server.WebviewServerConfig"),
                    "mainMenuUrl", MethodType.methodType(String.class));
            bound = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            bound = false;
            RaidLog.error("WebGUI is installed but its API is not what this mod expects ({}); web pages are disabled. "
                    + "Update CobbleRaids or use WebGUI 1.8.x.", e.toString());
        }
        return bound;
    }

    static boolean bound() {
        return bound;
    }

    /** Rescans WebGUI's page folder, so files this mod has just written are served. */
    static void reloadPages() {
        invoke(() -> reloadAssets.invoke());
    }

    /** True if the player's client has the WebGUI channel, i.e. an open request can reach it. */
    static boolean clientHasWebGui(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, OPEN_CHANNEL);
    }

    static void openGui(ServerPlayer player, String url) {
        invoke(() -> openGui.invoke(player, url));
    }

    static void emit(ServerPlayer player, String event, String json) {
        invoke(() -> emitToPage.invoke(player, event, json));
    }

    /** Sets the page this player's main-menu key (F6 by default) opens. */
    static void sendMainMenuUrl(ServerPlayer player, String url) {
        invoke(() -> sendMainMenu.invoke(player, url));
    }

    /** The main-menu page the server operator has set in WebGUI's own config; blank when they have not. */
    static String configuredMainMenuUrl() {
        if (!bound) return "";
        try {
            String url = (String) configuredMainMenu.invoke();
            return url == null ? "" : url;
        } catch (Throwable t) {
            return "";
        }
    }

    static void onPageEvent(String channel, BiConsumer<ServerPlayer, String> handler) {
        // Built once per channel rather than per event, so timing a page event allocates nothing.
        String section = "web:" + channel;
        BiConsumer<ServerPlayer, String> guarded = (player, json) -> {
            long started = RaidPerf.start();
            try {
                handler.accept(player, json);
            } catch (RuntimeException e) {
                // A page can send anything; a handler bug must not reach WebGUI's event bus.
                RaidLog.error("web page event '{}' failed: {}", channel, e.toString());
            } finally {
                RaidPerf.stop(section, started);
            }
        };
        invoke(() -> onPageEvent.invoke(channel, guarded));
    }

    private interface Call {
        void run() throws Throwable;
    }

    private static void invoke(Call call) {
        if (!bound) return;
        try {
            call.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("WebGUI call failed", t);
        }
    }
}
