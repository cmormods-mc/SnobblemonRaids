package com.cobbleraids.showdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the patcher against the failure that took a live 116-mod pack down twice.
 *
 * <p>mega_showdown replaces Cobblemon's unbundled index.js wholesale with its own
 * prettier-formatted copy: two-space indent and double quotes where Cobblemon's bundle uses tabs.
 * The original byte-exact matcher read that as "the simulator changed shape", threw on Cobblemon's
 * own Showdown thread where nothing catches, and took every battle in the game with it. The fix was
 * to match structurally. These fixtures are the shapes that have actually been seen in the wild, so
 * a future tightening of the patterns fails here in a second rather than on someone's server.
 *
 * <p>Both symptoms this prevents are silent by nature: an unpatched simulator gives raid bosses
 * that take no damage while turns proceed normally, and a mispatched one gives a JS syntax error
 * that surfaces only as a buried PolyglotException from GraalShowdownService.boot.
 */
class ShowdownIntegrationInstallerTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- fixtures

    /** Cobblemon's own bundle: tabs, LF, double-quoted strings. */
    private static String stockIndex() {
        return index("\t", "\n", '"');
    }

    /** mega_showdown's replacement copy: two-space indent, LF, double-quoted strings. */
    private static String megaShowdownIndex() {
        return index("  ", "\n", '"');
    }

    /**
     * Structurally faithful to Cobblemon's index.js: the three markers patchIndexBootstrap
     * requires, plus the stock output pump exactly as it appears before patching.
     */
    private static String index(String unit, String newline, char quote) {
        String q = String.valueOf(quote);
        String i1 = unit;
        String i2 = unit + unit;
        String i3 = unit + unit + unit;
        return String.join(newline,
                "const BS = require(" + q + "./sim/battle-stream" + q + ");",
                "const battleMap = new Map();",
                "",
                "function startBattle(battleId, messages) {",
                i1 + "const battleStream = new BS.BattleStream();",
                i1 + "battleMap.set(battleId, battleStream);",
                i1 + "(async () => {",
                i2 + "for await (const output of battleStream) {",
                i3 + "graalShowdown.sendFromShowdown(battleId, output);",
                i2 + "}",
                i1 + "})();",
                "}",
                "",
                "function sendBattleMessage(battleId, messages) {",
                i1 + "const battleStream = battleMap.get(battleId);",
                i1 + "for (const element of messages) {",
                i2 + "battleStream.write(element);",
                i1 + "}",
                "}",
                "");
    }

    /** Showdown's stock Format constructor line, before the raid branch is spliced in. */
    private static String dexFormats(char quote, String newline) {
        String q = String.valueOf(quote);
        return String.join(newline,
                "class Format {",
                "\tconstructor(data) {",
                "\t\tthis.gameType = data.gameType;",
                "\t\tthis.playerCount = this.gameType === " + q + "multi" + q
                        + " || this.gameType === " + q + "freeforall" + q + " ? 4 : 2;",
                "\t}",
                "}",
                "");
    }

    private Path write(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- index.js bootstrap hook

    @Nested
    @DisplayName("raid-patch hook")
    class BootstrapHook {

        @Test
        @DisplayName("is appended to Cobblemon's stock index.js")
        void appendsToStockIndex() throws IOException {
            Path path = write("index.js", stockIndex());
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);

            assertTrue(read(path).contains("require('./raid-patch');"));
        }

        @Test
        @DisplayName("is appended to mega_showdown's reformatted copy just the same")
        void appendsToMegaShowdownCopy() throws IOException {
            // The exact file shape that produced invulnerable bosses on a live server.
            Path path = write("index.js", megaShowdownIndex());
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);

            assertTrue(read(path).contains("require('./raid-patch');"));
        }

        @Test
        @DisplayName("is applied exactly once no matter how often the installer runs")
        void isIdempotent() throws IOException {
            // install() runs at unbundle time and again at SERVER_STARTED, and another mod's write
            // can land between them. Appending a second hook would re-run raid-patch.js.
            Path path = write("index.js", stockIndex());
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);
            String once = read(path);
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);

            assertEquals(once, read(path));
            assertEquals(1, countOccurrences(read(path), "require('./raid-patch');"));
        }

        @Test
        @DisplayName("is refused on a file that is not recognisably Cobblemon's bootstrap")
        void refusesUnrecognisedFile() throws IOException {
            Path path = write("index.js", "console.log('not showdown');\n");

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchIndexBootstrap(path));
            assertFalse(read(path).contains("raid-patch"), "a refused file must be left untouched");
        }

        @Test
        @DisplayName("is refused when the battle-stream require is gone")
        void refusesWhenBattleStreamRequireMissing() throws IOException {
            Path path = write("index.js", stockIndex().replace("./sim/battle-stream", "./sim/other"));

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchIndexBootstrap(path));
        }

        @Test
        @DisplayName("does not run the hook line onto existing code when the file has no trailing newline")
        void separatesFromUnterminatedLastLine() throws IOException {
            Path path = write("index.js", stockIndex().stripTrailing());
            ShowdownIntegrationInstaller.patchIndexBootstrap(path);

            String patched = read(path);
            assertFalse(patched.contains("}require('./raid-patch');"),
                    "hook must not be concatenated onto the previous statement");
            assertTrue(patched.lines().anyMatch(line -> line.trim().equals("require('./raid-patch');")));
        }
    }

    // ---------------------------------------------------------------- output pump

    @Nested
    @DisplayName("resilient output pump")
    class OutputPump {

        @Test
        @DisplayName("replaces Cobblemon's tab-indented pump")
        void patchesStockPump() throws IOException {
            Path path = write("index.js", stockIndex());
            ShowdownIntegrationInstaller.patchOutputPump(path);

            String patched = read(path);
            assertTrue(patched.contains("cobbleRaidsPumpErrors"));
            assertTrue(patched.contains("catch (err)"), "the pump must now catch");
            assertEquals(1, countOccurrences(patched, "sendFromShowdown"));
        }

        @Test
        @DisplayName("replaces mega_showdown's two-space pump, which the old exact matcher could not")
        void patchesMegaShowdownPump() throws IOException {
            // This is the regression. The byte-exact matcher failed here, threw inside
            // attemptUnbundle on Cobblemon's Showdown thread, and killed every battle in the game.
            Path path = write("index.js", megaShowdownIndex());
            ShowdownIntegrationInstaller.patchOutputPump(path);

            assertTrue(read(path).contains("cobbleRaidsPumpErrors"));
        }

        @Test
        @DisplayName("adopts the surrounding file's indentation")
        void adoptsSurroundingIndentation() throws IOException {
            Path tabs = write("tabs.js", stockIndex());
            Path spaces = write("spaces.js", megaShowdownIndex());
            ShowdownIntegrationInstaller.patchOutputPump(tabs);
            ShowdownIntegrationInstaller.patchOutputPump(spaces);

            assertTrue(read(tabs).contains("\t\tlet cobbleRaidsPumpErrors = 0;"),
                    "a tab-indented file should be patched with tabs");
            assertTrue(read(spaces).contains("    let cobbleRaidsPumpErrors = 0;"),
                    "a space-indented file should be patched with spaces");
        }

        @Test
        @DisplayName("preserves CRLF line endings")
        void preservesCrlf() throws IOException {
            Path path = write("index.js", index("\t", "\r\n", '\''));
            ShowdownIntegrationInstaller.patchOutputPump(path);

            String patched = read(path);
            assertTrue(patched.contains("cobbleRaidsPumpErrors"));
            assertFalse(patched.replace("\r\n", "").contains("\n"),
                    "patched region must not introduce bare LF into a CRLF file");
        }

        @Test
        @DisplayName("keeps the file's own identifier names rather than imposing ours")
        void keepsOriginalIdentifiers() throws IOException {
            String renamed = stockIndex()
                    .replace("battleStream", "bs")
                    .replace("graalShowdown", "bridge")
                    .replace("output", "msg");
            Path path = write("index.js", renamed);
            ShowdownIntegrationInstaller.patchOutputPump(path);

            String patched = read(path);
            assertTrue(patched.contains("for await (const msg of bs)"));
            assertTrue(patched.contains("bridge.sendFromShowdown(battleId, msg);"));
            assertFalse(patched.contains("graalShowdown"), "must not reintroduce Cobblemon's own names");
        }

        @Test
        @DisplayName("is applied exactly once no matter how often the installer runs")
        void isIdempotent() throws IOException {
            Path path = write("index.js", stockIndex());
            ShowdownIntegrationInstaller.patchOutputPump(path);
            String once = read(path);
            ShowdownIntegrationInstaller.patchOutputPump(path);

            assertEquals(once, read(path));
        }

        @Test
        @DisplayName("is refused when the pump signature is gone")
        void refusesMissingPump() throws IOException {
            Path path = write("index.js", stockIndex().replace("sendFromShowdown", "sendSomethingElse"));

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchOutputPump(path));
        }

        @Test
        @DisplayName("is refused when two pumps are present, rather than patching an ambiguous file")
        void refusesDuplicatePump() throws IOException {
            Path path = write("index.js", stockIndex() + stockIndex());

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchOutputPump(path));
        }

        @Test
        @DisplayName("leaves braces and parentheses balanced")
        void producesBalancedOutput() throws IOException {
            // A JS syntax error here does not surface as a syntax error: it appears as a buried
            // PolyglotException out of GraalShowdownService.boot, long after the patch ran.
            Path path = write("index.js", stockIndex());
            ShowdownIntegrationInstaller.patchOutputPump(path);
            String patched = read(path);

            assertEquals(countOccurrences(patched, "{"), countOccurrences(patched, "}"),
                    "unbalanced braces in patched index.js");
            assertEquals(countOccurrences(patched, "("), countOccurrences(patched, ")"),
                    "unbalanced parentheses in patched index.js");
        }
    }

    // ---------------------------------------------------------------- dex-formats playerCount

    @Nested
    @DisplayName("playerCount patch")
    class PlayerCount {

        @Test
        @DisplayName("teaches Showdown to honour a raid format's own player count")
        void patchesStockFormat() throws IOException {
            Path path = write("dex-formats.js", dexFormats('"', "\n"));
            ShowdownIntegrationInstaller.patchPlayerCount(path);

            String patched = read(path);
            assertTrue(patched.contains("Number.isInteger(data.playerCount)"));
            assertTrue(patched.contains("this.gameType === \"raid\""));
            assertTrue(patched.contains("? 4 : 2;"), "non-raid game types must behave exactly as before");
        }

        @Test
        @DisplayName("keeps the file's own quote style")
        void keepsQuoteStyle() throws IOException {
            Path path = write("dex-formats.js", dexFormats('\'', "\n"));
            ShowdownIntegrationInstaller.patchPlayerCount(path);

            String patched = read(path);
            assertTrue(patched.contains("this.gameType === 'raid'"));
            assertFalse(patched.contains("\"raid\""));
        }

        @Test
        @DisplayName("tolerates reformatted whitespace around the assignment")
        void toleratesReformattedWhitespace() throws IOException {
            String reformatted = dexFormats('"', "\n")
                    .replace("this.playerCount = ", "this . playerCount   =  ")
                    .replace("this.gameType ===", "this . gameType  ===");
            Path path = write("dex-formats.js", reformatted);
            ShowdownIntegrationInstaller.patchPlayerCount(path);

            assertTrue(read(path).contains("Number.isInteger(data.playerCount)"));
        }

        @Test
        @DisplayName("recognises a file an older CobbleRaids build already patched")
        void recognisesOlderBuildsWork() throws IOException {
            // The reason the applied-marker is the raid branch's own expression and not a comment
            // this build emits: upgrading must not read an older build's patch as an unknown
            // simulator and disable raids for everyone whose showdown/ directory already exists.
            Path path = write("dex-formats.js", dexFormats('"', "\n"));
            ShowdownIntegrationInstaller.patchPlayerCount(path);
            String once = read(path);

            ShowdownIntegrationInstaller.patchPlayerCount(path);

            assertEquals(once, read(path));
            assertEquals(1, countOccurrences(read(path), "Number.isInteger(data.playerCount)"));
        }

        @Test
        @DisplayName("is refused when the constructor signature is gone")
        void refusesUnknownFormat() throws IOException {
            Path path = write("dex-formats.js", "class Format { constructor(data) { this.x = 1; } }\n");

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchPlayerCount(path));
        }

        @Test
        @DisplayName("is refused when two candidate assignments are present")
        void refusesDuplicateSignature() throws IOException {
            Path path = write("dex-formats.js", dexFormats('"', "\n") + dexFormats('"', "\n"));

            assertThrows(IllegalStateException.class,
                    () -> ShowdownIntegrationInstaller.patchPlayerCount(path));
        }
    }

    // ---------------------------------------------------------------- combined

    @Test
    @DisplayName("both index.js edits coexist, in either order")
    void bothIndexEditsCoexist() throws IOException {
        Path forward = write("forward.js", megaShowdownIndex());
        ShowdownIntegrationInstaller.patchIndexBootstrap(forward);
        ShowdownIntegrationInstaller.patchOutputPump(forward);

        Path reverse = write("reverse.js", megaShowdownIndex());
        ShowdownIntegrationInstaller.patchOutputPump(reverse);
        ShowdownIntegrationInstaller.patchIndexBootstrap(reverse);

        for (Path path : new Path[] {forward, reverse}) {
            String patched = read(path);
            assertTrue(patched.contains("require('./raid-patch');"), path.getFileName().toString());
            assertTrue(patched.contains("cobbleRaidsPumpErrors"), path.getFileName().toString());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
