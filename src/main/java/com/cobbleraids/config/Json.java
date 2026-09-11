package com.cobbleraids.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Lenient readers for the two hand-written JSON formats this mod loads: the operator config and the
 * raid definitions.
 *
 * <p>Every reader falls back rather than throwing on a missing key, which is the behaviour both
 * formats depend on -- an operator's config from an older version is expected to be missing whatever
 * was added since, and a raid definition may omit anything that has a server-wide default. A
 * malformed *value* still throws, because a key someone wrote wrong should be reported, not
 * silently replaced with a default they did not choose.
 *
 * <p>These were duplicated verbatim in CobbleRaidsConfig and RaidDefinition.
 */
final class Json {

    private Json() {}

    /** A nested object, or an empty one so callers can read defaults out of it without null checks. */
    static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }

    /** A nested array, or an empty one. */
    static JsonArray array(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }

    static int integer(JsonObject root, String key, int fallback) {
        return root.has(key) ? root.get(key).getAsInt() : fallback;
    }

    static double decimal(JsonObject root, String key, double fallback) {
        return root.has(key) ? root.get(key).getAsDouble() : fallback;
    }

    static boolean bool(JsonObject root, String key, boolean fallback) {
        return root.has(key) ? root.get(key).getAsBoolean() : fallback;
    }

    static String string(JsonObject root, String key, String fallback) {
        return root.has(key) ? root.get(key).getAsString() : fallback;
    }
}
