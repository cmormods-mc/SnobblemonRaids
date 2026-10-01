package com.cobbleraids.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RaidKeyBindingsTest {

    private static final Path LANG = Path.of("src", "main", "resources", "assets", "cobbleraids", "lang", "en_us.json");

    @Test
    @DisplayName("the trophy room is on T and the leaderboard on L, as asked")
    void defaultKeys() {
        assertEquals(84, RaidKeyBindings.DEFAULT_TROPHY_KEY, "GLFW_KEY_T");
        assertEquals(76, RaidKeyBindings.DEFAULT_LEADERBOARD_KEY, "GLFW_KEY_L");
        assertNotEquals(RaidKeyBindings.DEFAULT_TROPHY_KEY, RaidKeyBindings.DEFAULT_LEADERBOARD_KEY);
    }

    @Test
    @DisplayName("both keys and their category have names in the language file, so the controls screen is not raw keys")
    void controlsScreenIsNamed() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8)).getAsJsonObject();

        for (String key : new String[] {RaidKeyBindings.TROPHY_NAME, RaidKeyBindings.LEADERBOARD_NAME, RaidKeyBindings.CATEGORY}) {
            assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), key + " has no display name");
        }
    }
}
