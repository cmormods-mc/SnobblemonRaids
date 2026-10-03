package com.cobbleraids.showdown;

import com.cobbleraids.api.showdown.ShowdownExtensions;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What other mods have asked to be installed into Showdown (see {@link ShowdownExtensions}).
 *
 * <p>Concurrent on purpose: modules are read on Cobblemon's own Showdown thread during unbundling, providers on
 * the server thread at battle start, and registration happens on whichever thread a mod initializes on.
 * Modules are kept in id order so the file set, and the order they load in, is the same on every boot.
 */
public final class ShowdownExtensionRegistry {

    /** What an extension id may look like; also what keeps it from escaping the directory as a file name. */
    static final Pattern MODULE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    private static final Map<String, Supplier<InputStream>> MODULES = new ConcurrentSkipListMap<>();
    private static final List<ShowdownExtensions.FormatFieldProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ShowdownExtensionRegistry() {}

    public static void registerModule(String id, Supplier<InputStream> source) {
        Objects.requireNonNull(source, "source");
        if (id == null || !MODULE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a Showdown extension id is 1-48 lowercase letters, digits, '_' or '-',"
                    + " starting with a letter or digit: " + id);
        }
        MODULES.put(id, source);
    }

    public static void registerProvider(ShowdownExtensions.FormatFieldProvider provider) {
        PROVIDERS.add(Objects.requireNonNull(provider, "provider"));
    }

    /** A snapshot, in id order. */
    public static Map<String, Supplier<InputStream>> modules() {
        // A LinkedHashMap, not Map.copyOf: copyOf does not preserve order, and the order is the point.
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(new java.util.TreeMap<>(MODULES)));
    }

    public static List<ShowdownExtensions.FormatFieldProvider> providers() {
        return List.copyOf(PROVIDERS);
    }

    /** Test seam: forget everything registered. */
    static void clearForTests() {
        MODULES.clear();
        PROVIDERS.clear();
    }
}
