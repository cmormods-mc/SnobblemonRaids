package com.cobbleraids.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebBundleSyncTest {
    @TempDir Path temp;

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void copiesNestedFilesIntoAnEmptyTarget() throws IOException {
        Path source = temp.resolve("src");
        Path target = temp.resolve("out");
        write(source.resolve("a.html"), "a");
        write(source.resolve("img/b.png"), "b");

        WebBundleSync.Result result = WebBundleSync.sync(source, target);

        assertEquals(2, result.written());
        assertEquals("b", Files.readString(target.resolve("img/b.png")));
    }

    @Test
    void leavesUnchangedFilesAloneAndRewritesChangedOnes() throws IOException {
        Path source = temp.resolve("src");
        Path target = temp.resolve("out");
        write(source.resolve("a.html"), "one");
        write(source.resolve("b.html"), "two");
        WebBundleSync.sync(source, target);

        write(source.resolve("b.html"), "three");
        WebBundleSync.Result result = WebBundleSync.sync(source, target);

        assertEquals(1, result.written());
        assertEquals(1, result.unchanged());
        assertEquals("three", Files.readString(target.resolve("b.html")));
    }

    /** A page dropped from the bundle must stop being served, not linger from an older build. */
    @Test
    void removesFilesTheBundleNoLongerHas() throws IOException {
        Path source = temp.resolve("src");
        Path target = temp.resolve("out");
        write(source.resolve("keep.html"), "k");
        write(target.resolve("old.html"), "stale");
        write(target.resolve("sub/old2.html"), "stale");

        WebBundleSync.Result result = WebBundleSync.sync(source, target);

        assertEquals(2, result.removed());
        assertFalse(Files.exists(target.resolve("old.html")));
        assertFalse(Files.exists(target.resolve("sub/old2.html")));
        assertTrue(Files.exists(target.resolve("keep.html")));
    }

    @Test
    void anIdenticalSecondRunChangesNothing() throws IOException {
        Path source = temp.resolve("src");
        Path target = temp.resolve("out");
        write(source.resolve("a.html"), "a");
        WebBundleSync.sync(source, target);

        assertFalse(WebBundleSync.sync(source, target).changed());
    }
}
