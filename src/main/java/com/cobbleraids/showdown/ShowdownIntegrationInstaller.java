package com.cobbleraids.showdown;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs CobbleRaids' integration into Cobblemon's unbundled Showdown copy: the raid-only
 * conditions file, the raid-patch.js hook (owns all boss healing), the playerCount patch in
 * dex-formats.js, and the resilient output pump in index.js. Every step is idempotent -- each
 * no-ops if its change is already present -- so calling install() again is always safe.
 *
 * <p>The two simulator edits are matched <em>structurally</em>, not by exact text. CobbleRaids is
 * not the only mod that rewrites these files: mega_showdown ships its own prettier-formatted
 * index.js (two-space indent where Cobblemon's bundle uses tabs) and drops it over Cobblemon's at
 * the same GraalShowdownUnbundler injection point. A byte-exact matcher sees that as "the simulator
 * changed shape" even though the code is identical, so the patterns below tolerate whitespace,
 * quoting and identifier differences and rebuild the replacement using whatever the file itself
 * uses.
 */
public final class ShowdownIntegrationInstaller {
    // patchPlayerCount, patchOutputPump and patchIndexBootstrap are package-private rather than
    // private so ShowdownIntegrationInstallerTest can drive them against real file fixtures. They
    // are the part of this mod most likely to break silently -- a simulator that loads without the
    // raid patch produces bosses that take no damage while the battle otherwise runs normally, with
    // nothing in the log -- and the alternative is finding that out on a live server.

    /** A JavaScript identifier, so a reformatted or renamed copy still matches. */
    private static final String ID = "[A-Za-z_$][A-Za-z0-9_$]*";

    private static final Pattern PLAYER_COUNT = Pattern.compile(
            "this\\s*\\.\\s*playerCount\\s*=\\s*"
            + "this\\s*\\.\\s*gameType\\s*===\\s*(['\"])multi\\1\\s*\\|\\|\\s*"
            + "this\\s*\\.\\s*gameType\\s*===\\s*(['\"])freeforall\\2\\s*\\?\\s*4\\s*:\\s*2\\s*;");
    /**
     * Marks a dex-formats.js this mod has already patched. Deliberately the raid branch's own
     * distinctive expression rather than an injected comment: a Showdown copy patched by an older
     * CobbleRaids build must be recognised as done, not rejected as unknown, or upgrading would
     * disable raids for everyone whose showdown/ directory already exists.
     */
    private static final Pattern PLAYER_COUNT_APPLIED = Pattern.compile(
            "Number\\s*\\.\\s*isInteger\\s*\\(\\s*data\\s*\\.\\s*playerCount\\s*\\)");
    private static final String INDEX_BATTLE_STREAM_MODULE = "./sim/battle-stream";
    private static final String INDEX_START_BATTLE = "function startBattle(";
    private static final String INDEX_SEND_BATTLE_MESSAGE = "function sendBattleMessage(";
    private static final String INDEX_RAID_HOOK = "require('./raid-patch');";

    /**
     * Cobblemon's stock JS->Java output pump. Group 1 is the loop variable, 2 the stream, 3 the
     * Java bridge object and 4 the battle id; the replacement is rebuilt from those so a renamed
     * or reindented copy is patched with its own names rather than ours.
     */
    private static final Pattern OUTPUT_PUMP = Pattern.compile(
            "\\(\\s*async\\s*\\(\\s*\\)\\s*=>\\s*\\{"
            + "\\s*for\\s+await\\s*\\(\\s*const\\s+(" + ID + ")\\s+of\\s+(" + ID + ")\\s*\\)\\s*\\{"
            + "\\s*(" + ID + ")\\s*\\.\\s*sendFromShowdown\\s*\\(\\s*(" + ID + ")\\s*,\\s*\\1\\s*\\)\\s*;?"
            + "\\s*\\}\\s*\\}\\s*\\)\\s*\\(\\s*\\)\\s*;");
    private static final String OUTPUT_PUMP_MARKER = "cobbleRaidsPumpErrors";

    /** False until a full install() has succeeded; raids refuse to spawn while it is false. */
    private static volatile boolean ready;

    private ShowdownIntegrationInstaller() {}

    /** Whether the Showdown edits raid battles depend on are actually in place. */
    public static boolean isReady() {
        return ready;
    }

    /**
     * Fail-closed: throws if Cobblemon's Showdown layout doesn't match what raid-patch.js depends
     * on, rather than patching a simulator we no longer recognise. Callers go through
     * {@link #installSafely(String)}, which turns that into "raids off" instead of "no battles at
     * all" -- see there for why that distinction matters.
     */
    public static void install() {
        ready = false;
        copy("/assets/cobbleraids/showdown/raid-patch.js", Path.of("showdown/raid-patch.js"));
        copy("/assets/cobbleraids/showdown/mods/conditions.js", Path.of("showdown/data/mods/cobblemon/conditions.js"));
        patchPlayerCount(Path.of("showdown/sim/dex-formats.js"));
        patchIndexBootstrap(Path.of("showdown/index.js"));
        patchOutputPump(Path.of("showdown/index.js"));
        ready = true;
    }

    /**
     * Runs install() without ever throwing, and records whether it worked.
     *
     * <p>Both call sites need this. The unbundle-time one runs on Cobblemon's own "Cobblemon
     * Showdown" thread, inside attemptUnbundle, and nothing up that stack catches: an exception
     * there kills the thread, ShowdownService never comes up, and <em>no battle of any kind</em>
     * works for the rest of the session. That is a wildly disproportionate way for CobbleRaids to
     * react to a file it merely failed to recognise, and it is exactly what happened to a real
     * 235-mod pack when mega_showdown's reformatted index.js defeated the old exact-text matcher.
     * Failing to install must cost raids, not Cobblemon.
     *
     * <p>The second call site is SERVER_STARTED -- after every other mod's own unbundle-time file
     * writes are guaranteed to be done, not just ours. Mods that patch the same unbundled files at
     * the same injection point (mega_showdown again) can silently overwrite our edits if their
     * Mixin happens to run after ours: confirmed live, index.js came out missing the raid-patch
     * hook on one boot and present on the next, same two mods, pure mod-load-order luck, with no
     * exception raised either way since the file stayed structurally valid. Re-running here repairs
     * a clobbered patch instead of leaving raid healing quietly broken for the whole session.
     */
    public static void installSafely(String phase) {
        try {
            install();
            System.out.println("[CobbleRaids] Showdown integration verified (" + phase + ").");
        } catch (RuntimeException ex) {
            System.err.println("[CobbleRaids] Showdown integration FAILED " + phase
                    + " -- raids are disabled for this session. Ordinary Cobblemon battles are"
                    + " unaffected. Cause: " + ex);
            ex.printStackTrace();
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

    /**
     * Lets a raid format declare its own player count instead of Showdown's fixed 2-or-4.
     *
     * <p>The raid branch reads {@code data.playerCount}, the constructor argument this assignment
     * already sits inside, so it is matched by name rather than captured -- but a copy that renamed
     * it would no longer contain the stock right-hand side either, and would be rejected below
     * rather than silently mispatched.
     */
    static void patchPlayerCount(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            // Idempotent on a second unbundle attempt, but never silently accept an unknown simulator layout.
            if (PLAYER_COUNT_APPLIED.matcher(source).find()) return;

            Matcher matcher = PLAYER_COUNT.matcher(source);
            if (!matcher.find()) {
                throw new IllegalStateException("Cobblemon Showdown playerCount signature changed; refusing to patch blindly");
            }
            String multi = matcher.group(1);
            String ffa = matcher.group(2);
            String replacement = "this.playerCount = this.gameType === "
                    + multi + "raid" + multi + " && Number.isInteger(data.playerCount) ? data.playerCount"
                    + " : this.gameType === " + multi + "multi" + multi
                    + " || this.gameType === " + ffa + "freeforall" + ffa + " ? 4 : 2;";
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) {
                throw new IllegalStateException("Unexpected duplicate Showdown playerCount signature");
            }
            Files.writeString(path, source.substring(0, start) + replacement + source.substring(end),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to patch Cobblemon Showdown playerCount handling", e);
        }
    }

    /**
     * Makes the single JS->Java output pump survive a recoverable simulator error.
     *
     * Cobblemon's startBattle() consumes the battle stream with a bare `for await` inside an
     * async IIFE that has no catch. That loop is the ONLY consumer of the stream: everything
     * Showdown emits reaches Java through it. Showdown, meanwhile, reports a simulator error
     * during a write as *recoverable* -- BattleStream#_write catches it and calls
     * pushError(err, true), which parks the error in the stream's errorBuf and deliberately
     * does NOT set atEOF, the contract being "the caller may keep reading". The next read
     * rethrows that error out of ObjectReadStream#loadIntoBuffer, out of next(), out of the
     * `for await`, and the IIFE rejects. Nothing awaits or catches that rejection, so the
     * consumer is gone permanently and silently. Every later push() then just accumulates in
     * an unread buffer: the simulator keeps running turns correctly and Java never hears
     * another word from the battle. Externally that looks exactly like a deadlock, with
     * nothing logged anywhere and no way to restart it from the Java side -- writes still
     * work, but the reader is what died.
     *
     * The replacement logs the error (previously invisible) and resumes the pump, which is
     * what `recoverable` was always meant to mean. Cost in the happy path is one assignment
     * per message: the try/catch spans the whole loop, not each message.
     *
     * Applied to Cobblemon's shared index.js, so it covers every battle, not just raids. That
     * is intended -- the only behaviour it changes is the case where the battle would
     * otherwise be irrecoverably dead.
     */
    static void patchOutputPump(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains(OUTPUT_PUMP_MARKER)) return;

            Matcher matcher = OUTPUT_PUMP.matcher(source);
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "Cobblemon Showdown output pump signature changed; refusing to patch blindly");
            }
            int start = matcher.start();
            int end = matcher.end();
            String replacement = resilientPump(
                    source, start, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
            if (matcher.find()) {
                throw new IllegalStateException("Unexpected duplicate Showdown output pump signature");
            }
            Files.writeString(path, source.substring(0, start) + replacement + source.substring(end),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to patch Cobblemon Showdown output pump", e);
        }
    }

    /**
     * Renders the replacement pump in the surrounding file's own style: its identifiers, its line
     * ending, and its indentation (tabs in Cobblemon's bundle, two spaces in mega_showdown's copy),
     * so the patched file stays readable for whoever opens it next to debug a battle.
     */
    private static String resilientPump(
            String source, int start, String output, String stream, String bridge, String battleId) {
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String lineStart = source.substring(source.lastIndexOf('\n', start - 1) + 1, start);
        String base = lineStart.isBlank() ? lineStart : "";
        String unit = base.contains("\t") ? "\t" : "  ";
        String i1 = base + unit;
        String i2 = i1 + unit;
        String i3 = i2 + unit;
        String i4 = i3 + unit;
        String errors = OUTPUT_PUMP_MARKER;

        return "// CobbleRaids: resilient output pump -- see ShowdownIntegrationInstaller#patchOutputPump." + newline
                + base + "(async () => {" + newline
                + i1 + "let " + errors + " = 0;" + newline
                + i1 + "for (;;) {" + newline
                + i2 + "try {" + newline
                + i3 + "for await (const " + output + " of " + stream + ") {" + newline
                + i4 + errors + " = 0;" + newline
                + i4 + bridge + ".sendFromShowdown(" + battleId + ", " + output + ");" + newline
                + i3 + "}" + newline
                + i3 + "return;" + newline
                + i2 + "} catch (err) {" + newline
                + i3 + errors + "++;" + newline
                + i3 + bridge + ".log('[CobbleRaids] Showdown output error #' + " + errors
                + " + ' in battle ' + " + battleId + " + ': ' + ((err && err.stack) || err));" + newline
                + i3 + "if (" + stream + ".atEOF || " + errors + " >= 20) return;" + newline
                + i2 + "}" + newline
                + i1 + "}" + newline
                + base + "})();";
    }

    static void patchIndexBootstrap(Path path) {
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
