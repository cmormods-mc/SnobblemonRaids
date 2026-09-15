package com.cobbleraids.reward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which reward GUI files an upgrade may replace.
 *
 * <p>The fixtures are the three defaults the mod actually shipped, taken from git history, so the
 * hashes in RewardGuiDefaults are checked against the real files rather than against themselves.
 */
class RewardGuiDefaultsTest {

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = RewardGuiDefaultsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return in.readAllBytes();
        }
    }

    private static byte[] withLineEndings(byte[] content, String newline) {
        String lf = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return lf.replace("\n", newline).getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"original", "crash-fix", "single-claim"})
    @DisplayName("every shipped default is recognised, from a Linux build or a Windows one")
    void supersededDefaultsAreRecognised(String version) throws IOException {
        byte[] shipped = resource("/skiesguis-defaults/" + version + ".json");

        assertTrue(RewardGuiDefaults.isSupersededDefault(withLineEndings(shipped, "\n")), version + " with LF");
        assertTrue(RewardGuiDefaults.isSupersededDefault(withLineEndings(shipped, "\r\n")),
                version + " with CRLF, as a jar built on Windows ships it");
    }

    @Test
    @DisplayName("a default an operator edited, even slightly, is left alone")
    void editedCopiesAreNotReplaced() throws IOException {
        String shipped = new String(resource("/skiesguis-defaults/single-claim.json"), StandardCharsets.UTF_8);

        assertFalse(RewardGuiDefaults.isSupersededDefault(
                shipped.replace("Choose Your Raid Reward!", "Pick a reward").getBytes(StandardCharsets.UTF_8)));
        assertFalse(RewardGuiDefaults.isSupersededDefault((shipped + " ").getBytes(StandardCharsets.UTF_8)),
                "trailing whitespace is still an edit");
    }

    @Test
    @DisplayName("the current bundled default is not itself marked superseded")
    void currentDefaultIsNotSuperseded() throws IOException {
        // If it were, every boot would rewrite it; more to the point, it would mean the list was
        // updated with the new hash instead of the outgoing one.
        assertFalse(RewardGuiDefaults.isSupersededDefault(
                resource("/assets/cobbleraids/skiesguis/cobbleraids_reward.json")));
    }

    @Test
    @DisplayName("nothing and empty content are not defaults")
    void emptyInput() {
        assertFalse(RewardGuiDefaults.isSupersededDefault(null));
        assertFalse(RewardGuiDefaults.isSupersededDefault(new ByteArrayOutputStream().toByteArray()));
    }
}
