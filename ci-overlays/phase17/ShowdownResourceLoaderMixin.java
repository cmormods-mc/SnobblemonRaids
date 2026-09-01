package com.cobbleraids.showdown;

import com.cobblemon.mod.common.battles.runner.graal.GraalShowdownUnbundler;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs only CobbleRaids' integration files after Cobblemon unbundles its own Showdown copy.
 * The one simulator edit is an exact-text, fail-fast patch against the supplied 1.7.3 dex-formats.js.
 */
@Mixin(GraalShowdownUnbundler.class)
public abstract class ShowdownResourceLoaderMixin {
    private static final String PLAYER_COUNT_173 =
            "this.playerCount = this.gameType === \"multi\" || this.gameType === \"freeforall\" ? 4 : 2;";
    private static final String PLAYER_COUNT_RAID =
            "this.playerCount = this.gameType === \"raid\" && Number.isInteger(data.playerCount) ? data.playerCount : this.gameType === \"multi\" || this.gameType === \"freeforall\" ? 4 : 2;";
    private static final String INDEX_BATTLE_STREAM_MODULE = "./sim/battle-stream";
    private static final String INDEX_START_BATTLE = "function startBattle(";
    private static final String INDEX_SEND_BATTLE_MESSAGE = "function sendBattleMessage(";
    private static final String INDEX_RAID_HOOK = "require('./raid-patch');";

    @Inject(method = "attemptUnbundle", at = @At("RETURN"))
    private void cobbleRaids$loadIntegrationFiles(CallbackInfo ci) {
        copy("/assets/cobbleraids/showdown/raid-patch.js", Path.of("showdown/raid-patch.js"));
        copy("/assets/cobbleraids/showdown/mods/conditions.js", Path.of("showdown/data/mods/cobblemon/conditions.js"));
        patchPlayerCount(Path.of("showdown/sim/dex-formats.js"));
        patchIndexBootstrap(Path.of("showdown/index.js"));
    }

    private static void copy(String resource, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            try (InputStream in = ShowdownResourceLoaderMixin.class.getResourceAsStream(resource)) {
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
