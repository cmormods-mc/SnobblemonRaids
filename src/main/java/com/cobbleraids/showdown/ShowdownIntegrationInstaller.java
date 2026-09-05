package com.cobbleraids.showdown;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Installs CobbleRaids' integration into Cobblemon's unbundled Showdown copy: the raid-only
 * conditions file, the raid-patch.js hook (owns all boss healing), and the exact-text playerCount
 * patch in dex-formats.js. Both methods below are idempotent -- each patch step already no-ops if
 * its change is already present -- so calling install() again is always safe.
 */
public final class ShowdownIntegrationInstaller {
    private static final String PLAYER_COUNT_173 =
            "this.playerCount = this.gameType === \"multi\" || this.gameType === \"freeforall\" ? 4 : 2;";
    private static final String PLAYER_COUNT_RAID =
            "this.playerCount = this.gameType === \"raid\" && Number.isInteger(data.playerCount) ? data.playerCount : this.gameType === \"multi\" || this.gameType === \"freeforall\" ? 4 : 2;";
    private static final String INDEX_BATTLE_STREAM_MODULE = "./sim/battle-stream";
    private static final String INDEX_START_BATTLE = "function startBattle(";
    private static final String INDEX_SEND_BATTLE_MESSAGE = "function sendBattleMessage(";
    private static final String INDEX_RAID_HOOK = "require('./raid-patch');";

    private ShowdownIntegrationInstaller() {}

    /**
     * Fail-closed: throws if Cobblemon's Showdown layout doesn't match what raid-patch.js depends
     * on. Called from ShowdownResourceLoaderMixin right after Cobblemon unbundles its own Showdown
     * copy -- the only point where "the simulator changed shape underneath us" can be caught early
     * and loudly instead of failing in a raid battle later.
     */
    public static void install() {
        copy("/assets/cobbleraids/showdown/raid-patch.js", Path.of("showdown/raid-patch.js"));
        copy("/assets/cobbleraids/showdown/mods/conditions.js", Path.of("showdown/data/mods/cobblemon/conditions.js"));
        patchPlayerCount(Path.of("showdown/sim/dex-formats.js"));
        patchIndexBootstrap(Path.of("showdown/index.js"));
    }

    /**
     * Best-effort re-check, called once from SERVER_STARTED -- after every mod's own
     * Showdown-unbundle-time file writes are guaranteed to be done, not just ours. Other mods that
     * also patch Cobblemon's unbundled index.js/dex-formats.js at the same GraalShowdownUnbundler
     * injection point (e.g. mega_showdown) can silently overwrite our patch if their own Mixin
     * injection happens to run after ours: confirmed live against a real modpack, index.js came out
     * missing the raid-patch hook on one server boot and present on another, same two mods, pure
     * mod-load-order luck, with no exception raised either way since the file was still structurally
     * valid at patch time. install() already no-ops on each step that's already applied, so calling
     * it again here repairs a clobbered patch instead of leaving raid healing silently broken for the
     * rest of the server's life. Deliberately non-fatal, unlike install() itself: if this fails, log
     * and move on rather than blowing up SERVER_STARTED for every other listener.
     */
    public static void ensureInstalled() {
        try {
            install();
            System.out.println("[CobbleRaids] Showdown integration verified at server start.");
        } catch (RuntimeException ex) {
            System.err.println("[CobbleRaids] Post-startup Showdown integration re-check failed, "
                    + "raid healing may not work correctly: " + ex.getMessage());
        }
    }

    private static void copy(String resource, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            try (InputStream in = ShowdownIntegrationInstaller.class.getResourceAsStream(resource)) {
                if (in == null) throw new FileNotFoundException(resource);
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to install CobbleRaids Showdown integration: " + resource, e);
        }
    }

    private static void patchPlayerCount(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            int first = source.indexOf(PLAYER_COUNT_173);
            int second = first < 0 ? -1 : source.indexOf(PLAYER_COUNT_173, first + 1);
            if (first < 0) {
                // Idempotent on a second unbundle attempt, but never silently accept an unknown simulator layout.
                if (source.contains(PLAYER_COUNT_RAID)) return;
                throw new IllegalStateException("Cobblemon Showdown playerCount signature changed; refusing to patch blindly");
            }
            if (second >= 0) throw new IllegalStateException("Unexpected duplicate Showdown playerCount signature");
            Files.writeString(path, source.replace(PLAYER_COUNT_173, PLAYER_COUNT_RAID), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to patch Cobblemon Showdown playerCount handling", e);
        }
    }

    private static void patchIndexBootstrap(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains(INDEX_RAID_HOOK)) return;

            // Do not depend on one exact import statement. Other Cobblemon addons may make
            // harmless edits to index.js while retaining the same Graal entry-point surface.
            // We still fail closed if this is not recognizably Cobblemon's Showdown bootstrap.
            boolean hasBattleStream = source.contains(INDEX_BATTLE_STREAM_MODULE);
            boolean hasStartBattle = source.contains(INDEX_START_BATTLE);
            boolean hasSendBattleMessage = source.contains(INDEX_SEND_BATTLE_MESSAGE);
            if (!hasBattleStream || !hasStartBattle || !hasSendBattleMessage) {
                throw new IllegalStateException(
                        "Cobblemon Showdown index.js structure is incompatible; refusing to install raid hook");
            }

            String separator = source.endsWith("\n") || source.endsWith("\r") ? "" : System.lineSeparator();
            Files.writeString(
                    path,
                    source + separator + INDEX_RAID_HOOK + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to install CobbleRaids Showdown bootstrap hook", e);
        }
    }
}
