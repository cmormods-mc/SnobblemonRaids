package com.cobbleraids.showdown;

import com.cobbleraids.api.showdown.ShowdownExtensions;
import com.cobbleraids.RaidLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adds extension fields to the {@code format} object of a battle's {@code >start} payload.
 *
 * <p>Pure: it works on the message array and the provider list, so every rule here is a unit test with no
 * Minecraft in reach. It never throws -- a payload it does not understand, a provider that throws, a value that
 * is not JSON all leave the battle starting exactly as it would have, because a battle that fails to start
 * because of someone else's extension is a far worse outcome than that extension not working.
 */
public final class ShowdownFormatExtras {

    static final String START = ">start ";
    static final int MAX_VALUE_CHARS = 8192;
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,31}");

    /** Names Cobblemon or CobbleRaids already use on the format object; an extension must not overwrite them. */
    private static final Set<String> RESERVED = Set.of(
            "mod", "gameType", "gen", "ruleset", "effectType", "name", "playerCount");

    private ShowdownFormatExtras() {}

    /**
     * @return {@code messages} itself when nothing was added, otherwise a copy with the {@code >start} line
     *         rewritten
     */
    public static String[] apply(String[] messages, UUID battleId, List<UUID> playerIds,
                                 List<ShowdownExtensions.FormatFieldProvider> providers) {
        if (providers.isEmpty()) return messages;
        int index = startLineIndex(messages);
        if (index < 0) return messages;

        JsonObject root;
        JsonObject format;
        try {
            root = JsonParser.parseString(messages[index].substring(START.length())).getAsJsonObject();
            format = root.getAsJsonObject("format");
        } catch (RuntimeException ex) {
            return messages;   // not a payload shape we know: leave it alone
        }
        if (format == null) return messages;

        boolean changed = false;
        for (ShowdownExtensions.FormatFieldProvider provider : providers) {
            Map<String, String> fields;
            try {
                fields = provider.fieldsFor(battleId, playerIds);
            } catch (Throwable ex) {
                RaidLog.error("A Showdown format-field provider threw; the battle starts without its fields.", ex);
                continue;
            }
            if (fields == null) continue;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (accept(format, field.getKey(), field.getValue())) changed = true;
            }
        }
        if (!changed) return messages;

        String[] copy = messages.clone();
        copy[index] = START + root;
        return copy;
    }

    private static boolean accept(JsonObject format, String name, String rawJson) {
        if (name == null || !FIELD_NAME.matcher(name).matches()) {
            RaidLog.warn("Ignoring a Showdown format field with an invalid name: {}", name);
            return false;
        }
        if (RESERVED.contains(name) || name.startsWith("raid") || format.has(name)) {
            RaidLog.warn("Ignoring Showdown format field '{}': the name is already taken or reserved.", name);
            return false;
        }
        if (rawJson == null || rawJson.length() > MAX_VALUE_CHARS) {
            RaidLog.warn("Ignoring Showdown format field '{}': no value, or over {} characters.", name, MAX_VALUE_CHARS);
            return false;
        }
        JsonElement value;
        try {
            value = JsonParser.parseString(rawJson);
        } catch (RuntimeException ex) {
            RaidLog.warn("Ignoring Showdown format field '{}': its value is not valid JSON.", name);
            return false;
        }
        format.add(name, value);
        return true;
    }

    private static int startLineIndex(String[] messages) {
        for (int i = 0; i < messages.length; i++) {
            if (messages[i] != null && messages[i].startsWith(START)) return i;
        }
        return -1;
    }
}
