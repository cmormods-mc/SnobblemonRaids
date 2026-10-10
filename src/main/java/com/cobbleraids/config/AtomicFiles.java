package com.cobbleraids.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Whole-file writes that cannot leave a half-written file behind.
 *
 * <p>{@code Files.writeString} truncates the target and then writes into it, so a crash or an error
 * midway leaves a stub. For an operator's config that stops the server booting; for Cobblemon's own
 * Showdown files it breaks every battle. Writing a sibling temporary file and moving it over the
 * target means the path always holds either the old complete content or the new complete content.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void writeString(Path path, String content) throws IOException {
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Still a single rename on one volume; it just cannot promise to be atomic.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
