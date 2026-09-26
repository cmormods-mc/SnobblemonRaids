package com.cobbleraids.config;

import com.cobbleraids.RaidLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The load-or-create-default-then-migrate-forward dance shared by every hand-edited JSON file this
 * mod owns: the operator config, the reward policy, and the shop catalogue. Was duplicated verbatim
 * across their three managers, each independently reimplementing the same read/write/migrate logic
 * for its own type -- {@link Json}, in this same package, already collapsed the equivalent
 * duplication for reading individual values out of one of these files; this collapses the file-level
 * mechanics around it.
 *
 * <p>Deliberately not {@code synchronized} itself: each caller's own {@code load()} stays
 * synchronized on that caller's own class, exactly as before this existed, so loading the config and
 * loading the shop catalogue are still independent locks rather than being serialized against each
 * other by a lock this class would otherwise own.
 */
public final class JsonFileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFileStore() {}

    /**
     * Reads {@code path}, creating it from {@code defaults} if missing, and writes the canonical
     * form back if it differs from what was on disk -- the migration write-back that lets an
     * operator's older file gain whatever settings this build added, which is the only way they
     * would discover a new one short of reading a changelog. A malformed file's exception is left
     * to the caller: every caller here keeps its last known good value rather than replacing it,
     * which only the caller can do since only it holds that value.
     *
     * @param label names the file in log lines and the failure message, e.g. "config", "reward policy"
     */
    public static <T> T load(Path path, String label, Supplier<T> defaults,
                             Function<JsonObject, T> fromJson, Function<T, JsonObject> toJson) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                T value = defaults.get();
                write(path, toJson.apply(value));
                RaidLog.info("Created default " + label + ": " + path);
                return value;
            }
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            T value = fromJson.apply(root);

            JsonObject canonical = toJson.apply(value);
            if (!canonical.equals(root)) {
                write(path, canonical);
                RaidLog.info("Updated " + path + " with settings new to this version.");
            }
            return value;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load " + label + " " + path, ex);
        }
    }

    private static void write(Path path, JsonObject json) throws Exception {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }
}
