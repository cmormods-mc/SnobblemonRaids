package com.cobbleraids.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Reading what a page sent. A page can send anything, so none of this throws: a message that is not
 * an object is null, a missing or mistyped field is empty or zero, and a string is cut to its limit.
 */
final class WebJson {
    private WebJson() {}

    static JsonObject object(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
            return null;
        }
    }

    static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** A string field no longer than {@code max}, so a payload codec's own limit can never be tripped. */
    static String bounded(JsonObject object, String key, int max) {
        String value = string(object, key);
        return value.length() <= max ? value : value.substring(0, max);
    }

    static int integer(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return 0;
        try {
            return object.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
