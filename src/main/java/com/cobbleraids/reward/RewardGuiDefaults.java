package com.cobbleraids.reward;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Recognises a reward GUI file that is exactly a default this mod once shipped, so an upgrade can
 * replace it without touching a file an operator has edited.
 *
 * <p>The installer only ever copied the default when no file existed, so a broken default stayed
 * on every server that had run the mod once. Matching the exact old bytes is what makes replacing
 * it safe: any edit at all, even whitespace, changes the hash and leaves the file alone.
 *
 * <p>Hashed with carriage returns stripped. A jar built on Windows ships the resource with CRLF line
 * endings (core.autocrlf) and one built on Linux ships LF, and both are the same default.
 *
 * <p>Free of Minecraft and SkiesGUIs types so it can be tested without either.
 */
public final class RewardGuiDefaults {

    /**
     * Every default shipped before the current one. <b>When the bundled file changes, add the
     * outgoing version's hash here</b>, or servers that already have it keep the old copy forever.
     *
     * <p>All three sent their messages as COMMAND_PLAYER tellraw, which runs with the player's own
     * permissions, so no ordinary player ever saw them. The first also had actions that crashed the
     * server.
     */
    private static final Set<String> SUPERSEDED = Set.of(
            "13ec5c67a41fa32737a49cd25e79d0d53d13662a37f3be447b779cfc415b0546", // original, crashing actions
            "38b0c7f6ce1ee9acea1e2db22fc9be6b4ef8c219c041f3cd623ccd49638621b0", // after the crash fix
            "1383bda9c4fa8e1a8027c994234230ac6d6408c0c3ddfd20a2e3ec420b6e03d5"  // single claim option
    );

    private RewardGuiDefaults() {}

    /** True when {@code content} is byte-for-byte a superseded default, ignoring line endings. */
    public static boolean isSupersededDefault(byte[] content) {
        return content != null && SUPERSEDED.contains(normalizedSha256(content));
    }

    static String normalizedSha256(byte[] content) {
        ByteArrayOutputStream stripped = new ByteArrayOutputStream(content.length);
        for (byte b : content) {
            if (b != '\r') stripped.write(b);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stripped.toByteArray()));
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(ex);
        }
    }
}
