package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JsonFileStore rewrites an operator's file in canonical form at startup whenever this build
 * serialises it differently. If that write is interrupted -- a crash, a full disk, a serialisation
 * error -- the operator's file must still be the one they wrote, not a truncated stub that stops
 * the server booting. These tests pin that.
 */
class JsonFileStoreTest {

    /** Gson refuses to serialise an unknown JsonElement subclass, partway through writing the object. */
    private static final class Unwritable extends JsonElement {
        @Override
        public JsonElement deepCopy() {
            return this;
        }
    }

    private static JsonObject unwritableObject() {
        JsonObject poisoned = new JsonObject();
        poisoned.addProperty("first", "written before the failure");
        poisoned.add("second", new Unwritable());
        return poisoned;
    }

    @Test
    @DisplayName("a failed migration write-back leaves the operator's file untouched")
    void failedWriteBackKeepsOriginal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.json");
        String original = "{\"operatorSetting\": 42}";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () ->
                JsonFileStore.load(file, "test", () -> "default", root -> "parsed", value -> unwritableObject()));

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a failed write leaves no temporary file behind")
    void failedWriteLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"a\": 1}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () ->
                JsonFileStore.load(file, "test", () -> "default", root -> "parsed", value -> unwritableObject()));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    @DisplayName("a successful migration still rewrites the file in canonical form")
    void successfulWriteBackReplacesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"a\": 1}", StandardCharsets.UTF_8);

        JsonFileStore.load(file, "test", () -> "default", root -> "parsed", value -> {
            JsonObject canonical = new JsonObject();
            canonical.addProperty("a", 1);
            canonical.addProperty("addedByThisBuild", true);
            return canonical;
        });

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("addedByThisBuild"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    @DisplayName("a missing file is created from the defaults")
    void missingFileCreatedFromDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve("server.json");

        String result = JsonFileStore.load(file, "test", () -> "default", root -> "parsed", value -> {
            JsonObject json = new JsonObject();
            json.addProperty("fresh", true);
            return json;
        });

        assertEquals("default", result);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("fresh"));
    }
}
