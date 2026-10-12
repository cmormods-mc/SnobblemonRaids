package com.cobbleraids.web;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes one directory an exact copy of another, touching only what differs.
 *
 * <p>WebGUI serves pages from a folder on disk, so the pages this mod ships have to be put there.
 * The target is wholly ours, so files that are no longer in the bundle are removed: a page left
 * over from an older build would otherwise keep being served, and its manifest entry would be sent
 * to every joining player. Unchanged files are left alone, so a restart does not rewrite them and
 * does not change what WebGUI hashes.
 *
 * <p>Free of Minecraft types so it can be unit tested against temporary directories.
 */
public final class WebBundleSync {
    private WebBundleSync() {}

    /** What a sync did, for the log. */
    public record Result(int written, int unchanged, int removed) {
        public boolean changed() {
            return written > 0 || removed > 0;
        }
    }

    public static Result sync(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Set<Path> wanted = new HashSet<>();
        int[] counts = new int[2];

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = relative(source, file);
                Path destination = target.resolve(relative.toString()).normalize();
                // A bundle entry cannot be allowed to name something outside the target folder.
                if (!destination.startsWith(target.normalize())) {
                    throw new IOException("bundle entry escapes the target folder: " + relative);
                }
                wanted.add(destination);
                byte[] bytes = Files.readAllBytes(file);
                if (Files.isRegularFile(destination) && Arrays.equals(Files.readAllBytes(destination), bytes)) {
                    counts[1]++;
                    return FileVisitResult.CONTINUE;
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
                counts[0]++;
                return FileVisitResult.CONTINUE;
            }
        });

        int removed = removeStale(target, wanted);
        return new Result(counts[0], counts[1], removed);
    }

    /** Relative path with the same separators whichever filesystem the source lives on. */
    private static Path relative(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return Path.of(rel);
    }

    private static int removeStale(Path target, Set<Path> wanted) throws IOException {
        List<Path> stale = new ArrayList<>();
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!wanted.contains(file.normalize())) stale.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path file : stale) Files.deleteIfExists(file);
        return stale.size();
    }
}
