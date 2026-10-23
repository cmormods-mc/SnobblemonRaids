package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's side of the WebGUI pages: ships this mod's page bundle into WebGUI's folder and opens
 * pages for players whose client can show them.
 *
 * <p>Pages live in this jar under {@code web/} and are served to clients as
 * {@code webgui:/cobbleraids/<page>}. WebGUI sends them down the player's own connection, so there
 * is no web host and no open port to run.
 */
public final class WebUi {
    private WebUi() {}

    private static final String MOD_ID = "cobbleraids";
    private static final String WEBGUI_ID = "webgui";
    /** Folder under WebGUI's page root that this mod owns outright; sync deletes anything else in it. */
    private static final String PAGE_FOLDER = "cobbleraids";

    /** True once WebGUI is installed and every call this mod makes into it has been resolved. */
    public static boolean available() {
        return WebGuiBridge.bound();
    }

    /** The address of one of this mod's pages. */
    public static String pageUrl(String page) {
        return "webgui:/" + PAGE_FOLDER + "/" + page;
    }

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded(WEBGUI_ID)) {
            RaidLog.info("WebGUI is not installed; web pages are disabled.");
            return;
        }
        if (!WebGuiBridge.bind()) return;
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                RaidFaultBarrier.guard("startup:web-bundle", WebUi::installBundle));
        ShopWebPage.register();
        TrophyWebPage.register();
        LegendWebPage.register();
        RewardWebPage.register();
        CaptureWebPage.register();
        LeaderboardWebPage.register();
        HubWebPage.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                RaidFaultBarrier.guard("player-disconnect:web-icons",
                        () -> {
                            WebIconFeed.forgetEverywhere(handler.getPlayer().getUUID());
                            RewardWebPage.forget(handler.getPlayer().getUUID());
                            CaptureWebPage.forget(handler.getPlayer().getUUID());
                            LeaderboardWebPage.forget(handler.getPlayer().getUUID());
                        }));
    }

    /**
     * Copies the bundled pages into WebGUI's page folder and has WebGUI rescan it.
     *
     * <p>Done on every start rather than once, so an updated jar is what gets served. WebGUI does its
     * own rescan during the same event; whichever listener runs second sees the other's work, so the
     * order between the two mods does not matter.
     */
    private static void installBundle() {
        Optional<ModContainer> self = FabricLoader.getInstance().getModContainer(MOD_ID);
        Optional<Path> source = self.flatMap(mod -> mod.findPath("web/" + PAGE_FOLDER));
        if (source.isEmpty() || !Files.isDirectory(source.get())) {
            RaidLog.warn("The web page bundle is missing from the jar; no pages were installed.");
            return;
        }
        Path target = FabricLoader.getInstance().getConfigDir().resolve("webgui").resolve("web").resolve(PAGE_FOLDER);
        try {
            WebBundleSync.Result result = WebBundleSync.sync(source.get(), target);
            RaidLog.info("Web pages: {} written, {} unchanged, {} removed ({}).",
                    result.written(), result.unchanged(), result.removed(), target);
        } catch (java.io.IOException e) {
            RaidLog.error("Could not install the web pages into {}: {}", target, e.toString());
            return;
        }
        WebGuiBridge.reloadPages();
    }

    /** Whether this player can be shown a page at all. */
    public static boolean canShow(ServerPlayer player) {
        return available() && WebGuiBridge.clientHasWebGui(player);
    }

    /** Opens a page full-screen. Returns false, sending nothing, if the client cannot show it. */
    public static boolean open(ServerPlayer player, String page) {
        if (!canShow(player)) return false;
        WebGuiBridge.openGui(player, pageUrl(page));
        return true;
    }
}
