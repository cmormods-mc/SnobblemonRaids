package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @Test
    @DisplayName("writes a new file and replaces an existing one, leaving no temporary file")
    void writeAndReplace(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("index.js");

        AtomicFiles.writeString(file, "first");
        assertEquals("first", Files.readString(file, StandardCharsets.UTF_8));

        AtomicFiles.writeString(file, "second");
        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    @DisplayName("a write that cannot complete leaves the target as it was and no temporary file")
    void failedWriteKeepsOriginal(@TempDir Path dir) throws IOException {
        // A non-empty directory cannot be replaced by a file move, so the move step fails.
        Path target = dir.resolve("target");
        Files.createDirectory(target);
        Path child = target.resolve("child");
        Files.writeString(child, "untouched", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> AtomicFiles.writeString(target, "content"));

        assertEquals("untouched", Files.readString(child, StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}
