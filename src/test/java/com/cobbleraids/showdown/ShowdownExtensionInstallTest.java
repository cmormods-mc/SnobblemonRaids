package com.cobbleraids.showdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Other mods' Showdown modules: written beside raid-patch.js, cleaned up, and never able to hurt the install. */
class ShowdownExtensionInstallTest {

    @TempDir
    Path showdown;

    @BeforeEach
    void clean() {
        ShowdownExtensionRegistry.clearForTests();
    }

    private static Supplier<InputStream> source(String text) {
        return () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("each registered module is written as ext-<id>.js")
    void writesModules() throws Exception {
        Map<String, Supplier<InputStream>> modules = new LinkedHashMap<>();
        modules.put("cobbletowers-fx", source("// tower"));
        modules.put("other", source("// other"));

        ShowdownIntegrationInstaller.installExtensions(showdown, modules);

        assertEquals("// tower", Files.readString(showdown.resolve("ext-cobbletowers-fx.js")));
        assertEquals("// other", Files.readString(showdown.resolve("ext-other.js")));
    }

    @Test
    @DisplayName("reinstalling is idempotent and picks up a changed source")
    void reinstallReplaces() throws Exception {
        ShowdownIntegrationInstaller.installExtensions(showdown, Map.of("a", source("// one")));
        ShowdownIntegrationInstaller.installExtensions(showdown, Map.of("a", source("// two")));

        assertEquals("// two", Files.readString(showdown.resolve("ext-a.js")));
    }

    @Test
    @DisplayName("a module that is no longer registered is removed, so an uninstalled mod stops loading its patch")
    void staleModulesAreRemoved() throws Exception {
        ShowdownIntegrationInstaller.installExtensions(showdown, Map.of("old", source("// old"), "kept", source("// kept")));
        ShowdownIntegrationInstaller.installExtensions(showdown, Map.of("kept", source("// kept")));

        assertFalse(Files.exists(showdown.resolve("ext-old.js")));
        assertTrue(Files.exists(showdown.resolve("ext-kept.js")));
    }

    @Test
    @DisplayName("cleaning up touches only ext-*.js: raid-patch.js and every other file are left alone")
    void otherFilesAreUntouched() throws Exception {
        Files.writeString(showdown.resolve("raid-patch.js"), "// raid");
        Files.writeString(showdown.resolve("index.js"), "// index");
        Files.writeString(showdown.resolve("extra.js"), "// not ours");
        Files.writeString(showdown.resolve("ext-stale.txt"), "// wrong suffix");

        ShowdownIntegrationInstaller.installExtensions(showdown, Map.of());

        for (String name : new String[] {"raid-patch.js", "index.js", "extra.js", "ext-stale.txt"}) {
            assertTrue(Files.exists(showdown.resolve(name)), name + " must survive");
        }
    }

    @Test
    @DisplayName("a module whose source cannot be read costs only that module: the others still install")
    void brokenModuleDoesNotBlockTheRest() throws Exception {
        Map<String, Supplier<InputStream>> modules = new LinkedHashMap<>();
        modules.put("broken", () -> {
            throw new IllegalStateException("jar closed");
        });
        modules.put("empty", () -> null);
        modules.put("fine", source("// fine"));

        ShowdownIntegrationInstaller.installExtensions(showdown, modules);

        assertFalse(Files.exists(showdown.resolve("ext-broken.js")));
        assertFalse(Files.exists(showdown.resolve("ext-empty.js")));
        assertEquals("// fine", Files.readString(showdown.resolve("ext-fine.js")));
    }

    @Test
    @DisplayName("a missing showdown directory is a quiet no-op, never an exception")
    void missingDirectory() {
        ShowdownIntegrationInstaller.installExtensions(showdown.resolve("nowhere"), Map.of("a", source("// a")));
    }

    @Test
    @DisplayName("an extension id that could escape the directory or break the loader's pattern is refused at registration")
    void badIdsAreRefused() {
        for (String id : new String[] {"../evil", "has space", "UPPER", "", "-lead", "dot.dot", "a/b",
                "x".repeat(49)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShowdownExtensionRegistry.registerModule(id, source("//")), "id '" + id + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> ShowdownExtensionRegistry.registerModule(null, source("//")));
        ShowdownExtensionRegistry.registerModule("cobbletowers-fx", source("//"));
        ShowdownExtensionRegistry.registerModule("a1_b-2", source("//"));
        assertEquals(2, ShowdownExtensionRegistry.modules().size());
    }

    @Test
    @DisplayName("registering an id again replaces the earlier source, and modules come back in id order")
    void registryOrderAndReplace() throws Exception {
        ShowdownExtensionRegistry.registerModule("b", source("// b1"));
        ShowdownExtensionRegistry.registerModule("a", source("// a"));
        ShowdownExtensionRegistry.registerModule("b", source("// b2"));

        assertEquals(java.util.List.of("a", "b"), java.util.List.copyOf(ShowdownExtensionRegistry.modules().keySet()));
        try (InputStream in = ShowdownExtensionRegistry.modules().get("b").get()) {
            assertEquals("// b2", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("extensions.js lists exactly the modules that were written, in order, and is written even when empty")
    void manifestListsInstalledModules() throws Exception {
        ShowdownIntegrationInstaller.installExtensions(showdown, new LinkedHashMap<>());
        assertTrue(Files.readString(showdown.resolve("extensions.js")).contains("module.exports = [];"));

        Map<String, Supplier<InputStream>> modules = new LinkedHashMap<>();
        modules.put("zeta", source("// z"));
        modules.put("alpha", source("// a"));
        modules.put("broken", () -> null);   // no source: not written, so must not be listed
        ShowdownIntegrationInstaller.installExtensions(showdown, modules);
        assertTrue(Files.readString(showdown.resolve("extensions.js")).contains("module.exports = [\"alpha\", \"zeta\"];"),
                Files.readString(showdown.resolve("extensions.js")));

        ShowdownIntegrationInstaller.installExtensions(showdown, new LinkedHashMap<>());
        assertTrue(Files.readString(showdown.resolve("extensions.js")).contains("module.exports = [];"),
                "a removed mod's entry does not linger");
        assertFalse(Files.exists(showdown.resolve("ext-alpha.js")));
    }

    @Test
    @DisplayName("raid-patch.js never requires a Node built-in: Showdown runs in GraalJS, where require('fs') throws")
    void loaderUsesNoNodeBuiltins() throws Exception {
        try (InputStream in = ShowdownExtensionInstallTest.class.getResourceAsStream("/assets/cobbleraids/showdown/raid-patch.js")) {
            String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String builtin : new String[] {"fs", "path", "os", "child_process", "util", "crypto"}) {
                assertFalse(js.contains("require('" + builtin + "')") || js.contains("require(\"" + builtin + "\")"),
                        "raid-patch.js requires the Node built-in '" + builtin + "'");
            }
        }
    }
}
