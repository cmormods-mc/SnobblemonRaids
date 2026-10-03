package com.cobbleraids.showdown;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.api.showdown.ShowdownExtensions.FormatFieldProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What another mod's format fields may do to a battle's start payload, and above all what they may not: a
 * provider's mistake must never stop a battle from starting.
 */
class ShowdownFormatExtrasTest {

    private static final UUID BATTLE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String START =
            ">start { \"format\": {\"mod\":\"gen9\",\"gameType\":\"singles\",\"gen\":9,\"ruleset\":[],\"effectType\":\"Format\"} }";

    private static String[] messages() {
        return new String[] {START, ">player p1 {\"name\":\"a\"}", ">player p2 {\"name\":\"b\"}"};
    }

    private static JsonObject format(String[] result) {
        return JsonParser.parseString(result[0].substring(">start ".length())).getAsJsonObject().getAsJsonObject("format");
    }

    private static FormatFieldProvider provide(Map<String, String> fields) {
        return (battle, players) -> fields;
    }

    @Test
    @DisplayName("with no providers the very same array comes back untouched")
    void noProviders() {
        String[] in = messages();

        assertSame(in, ShowdownFormatExtras.apply(in, BATTLE, List.of(PLAYER), List.of()));
    }

    @Test
    @DisplayName("a provider's field lands on the format object, the rest of the payload and the other lines unchanged")
    void addsField() {
        String[] in = messages();

        String[] out = ShowdownFormatExtras.apply(in, BATTLE, List.of(PLAYER),
                List.of(provide(Map.of("towerFx", "[{\"op\":\"weather\",\"id\":\"raindance\"}]"))));

        assertEquals("raindance", format(out).getAsJsonArray("towerFx").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("gen9", format(out).get("mod").getAsString());
        assertEquals(9, format(out).get("gen").getAsInt());
        assertEquals(in[1], out[1]);
        assertEquals(in[2], out[2]);
        assertEquals(START, in[0], "the input array was not modified");
    }

    @Test
    @DisplayName("a provider is told the battle and who is in it, so it can tell whether the battle is its own")
    void providerSeesTheBattle() {
        UUID[] seen = new UUID[1];
        List<UUID>[] seenPlayers = new List[1];
        FormatFieldProvider spy = (battle, players) -> {
            seen[0] = battle;
            seenPlayers[0] = players;
            return Map.of();
        };

        ShowdownFormatExtras.apply(messages(), BATTLE, List.of(PLAYER), List.of(spy));

        assertEquals(BATTLE, seen[0]);
        assertEquals(List.of(PLAYER), seenPlayers[0]);
    }

    @Test
    @DisplayName("names Cobblemon or CobbleRaids use, raid-prefixed names, and names already present are not overwritten")
    void reservedNamesAreProtected() {
        String[] out = ShowdownFormatExtras.apply(messages(), BATTLE, List.of(PLAYER), List.of(provide(Map.of(
                "gameType", "\"doubles\"", "mod", "\"evil\"", "ruleset", "[\"x\"]", "raidWeather", "\"raindance\"",
                "playerCount", "9", "good", "1"))));

        JsonObject format = format(out);
        assertEquals("singles", format.get("gameType").getAsString());
        assertEquals("gen9", format.get("mod").getAsString());
        assertEquals(0, format.getAsJsonArray("ruleset").size());
        assertFalse(format.has("raidWeather"));
        assertFalse(format.has("playerCount"));
        assertEquals(1, format.get("good").getAsInt(), "an ordinary name still goes through");
    }

    @Test
    @DisplayName("the first provider to set a name wins")
    void firstProviderWins() {
        String[] out = ShowdownFormatExtras.apply(messages(), BATTLE, List.of(PLAYER),
                List.of(provide(Map.of("shared", "1")), provide(Map.of("shared", "2"))));

        assertEquals(1, format(out).get("shared").getAsInt());
    }

    @Test
    @DisplayName("an invalid name, a value that is not JSON, an oversized value and a null are each dropped, not fatal")
    void badFieldsAreDropped() {
        String huge = "\"" + "x".repeat(ShowdownFormatExtras.MAX_VALUE_CHARS) + "\"";
        Map<String, String> fields = new java.util.HashMap<>();
        fields.put("1bad", "1");
        fields.put("has space", "1");
        fields.put("notJson", "{oops");
        fields.put("huge", huge);
        fields.put("nothing", null);
        fields.put("fine", "{\"a\":[1,2]}");

        String[] out = ShowdownFormatExtras.apply(messages(), BATTLE, List.of(PLAYER), List.of(provide(fields)));

        JsonObject format = format(out);
        for (String name : List.of("1bad", "has space", "notJson", "huge", "nothing")) assertFalse(format.has(name), name);
        assertTrue(format.has("fine"));
    }

    @Test
    @DisplayName("a provider that throws is skipped and the others still apply")
    void throwingProviderIsSkipped() {
        FormatFieldProvider boom = (battle, players) -> {
            throw new IllegalStateException("broken extension");
        };

        String[] out = ShowdownFormatExtras.apply(messages(), BATTLE, List.of(PLAYER),
                List.of(boom, provide(Map.of("survivor", "true"))));

        assertTrue(format(out).get("survivor").getAsBoolean());
    }

    @Test
    @DisplayName("a provider that adds nothing leaves the very same array, so an ordinary battle is untouched")
    void nothingAddedMeansUntouched() {
        String[] in = messages();

        assertSame(in, ShowdownFormatExtras.apply(in, BATTLE, List.of(PLAYER), List.of(provide(Map.of()))));
    }

    @Test
    @DisplayName("a payload with no >start line, or one that is not the shape we know, is passed through unchanged")
    void unknownPayloadShapes() {
        String[] none = {">player p1 {}", ">player p2 {}"};
        String[] garbled = {">start {not json", ">player p1 {}"};
        String[] noFormat = {">start {\"other\": 1}"};
        List<FormatFieldProvider> providers = List.of(provide(Map.of("x", "1")));

        assertArrayEquals(none, ShowdownFormatExtras.apply(none, BATTLE, List.of(), providers));
        assertArrayEquals(garbled, ShowdownFormatExtras.apply(garbled, BATTLE, List.of(), providers));
        assertArrayEquals(noFormat, ShowdownFormatExtras.apply(noFormat, BATTLE, List.of(), providers));
    }

    @Test
    @DisplayName("a null message in the array does not break the search for the start line")
    void nullMessageTolerated() {
        String[] in = {null, START};

        assertEquals(1, ShowdownFormatExtras.apply(in, BATTLE, List.of(), List.of(provide(Map.of("x", "1")))).length - 1);
    }
}
