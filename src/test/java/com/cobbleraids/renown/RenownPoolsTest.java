package com.cobbleraids.renown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How renown word lists load and how a title is drawn from them.
 *
 * <p>The loader's promise is the one raid definitions make: bad content costs only itself. The
 * draw's is that tier and type restrictions actually hold, since a starter boss called "the
 * Sovereign" is exactly the kind of bug nobody would ever file.
 */
class RenownPoolsTest {

    private static RenownPools parse(String json, List<String> warnings) {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        files.put("test:renown/a.json", JsonParser.parseString(json).getAsJsonObject());
        return RenownPools.parse(files, warnings::add);
    }

    @Test
    @DisplayName("a tier-restricted name or epithet never reaches another tier")
    void tierRestrictionsHold() {
        RenownPools pools = parse("""
                {"names": [{"values": ["Kaelen"]}, {"tiers": ["mythical"], "values": ["Aurex"]}],
                 "epithets": [{"text": "the Grim", "boon": "hp_pool"},
                              {"text": "the Sovereign", "tiers": ["mythical"], "boon": "hp_pool"}]}
                """, new ArrayList<>());
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 2_000; i++) {
            RaidRenown starter = pools.draw(RaidRarityTier.STARTER, List.of(), random).orElseThrow();
            assertEquals("Kaelen", starter.name());
            assertEquals("the Grim", starter.epithet());
        }
        boolean sawGrand = false;
        for (int i = 0; i < 2_000 && !sawGrand; i++) {
            RaidRenown mythical = pools.draw(RaidRarityTier.MYTHICAL, List.of(), random).orElseThrow();
            sawGrand = mythical.name().equals("Aurex") && mythical.epithet().equals("the Sovereign");
        }
        assertTrue(sawGrand, "a mythical boss should be able to draw its own tier's entries");
    }

    @Test
    @DisplayName("a typed epithet only suits a boss that shares a type, and joins rather than replaces")
    void typedEpithets() {
        RenownPools pools = parse("""
                {"names": {"values": ["Kaelen"]},
                 "epithets": [{"text": "the Grim", "boon": "hp_pool"},
                              {"text": "the Tidebreaker", "types": ["water"], "boon": "stat_focus:attack"}]}
                """, new ArrayList<>());
        SplittableRandom random = new SplittableRandom(11);
        boolean fireSawTide = false, waterSawTide = false, waterSawGrim = false;
        for (int i = 0; i < 2_000; i++) {
            fireSawTide |= pools.draw(RaidRarityTier.STARTER, List.of("fire"), random).orElseThrow().epithet().equals("the Tidebreaker");
            String water = pools.draw(RaidRarityTier.STARTER, List.of("water", "ground"), random).orElseThrow().epithet();
            waterSawTide |= water.equals("the Tidebreaker");
            waterSawGrim |= water.equals("the Grim");
        }
        assertFalse(fireSawTide);
        assertTrue(waterSawTide);
        assertTrue(waterSawGrim, "a typed epithet must not crowd out the general ones");
    }

    @Test
    @DisplayName("epithet weights decide the odds")
    void weightsAreHonoured() {
        RenownPools pools = parse("""
                {"names": {"values": ["Kaelen"]},
                 "epithets": [{"text": "the Rare", "weight": 1, "boon": "hp_pool"},
                              {"text": "the Common", "weight": 9, "boon": "hp_pool"}]}
                """, new ArrayList<>());
        SplittableRandom random = new SplittableRandom(3);
        int rare = 0, draws = 20_000;
        for (int i = 0; i < draws; i++) {
            if (pools.draw(RaidRarityTier.LEGENDARY, List.of(), random).orElseThrow().epithet().equals("the Rare")) rare++;
        }
        assertEquals(0.10, rare / (double) draws, 0.01);
    }

    @Test
    @DisplayName("bad entries are dropped with a warning and the rest still load")
    void badEntriesCostOnlyThemselves() {
        List<String> warnings = new ArrayList<>();
        RenownPools pools = parse("""
                {"names": [{"values": ["Kaelen", "lowercase", "Has,Comma", "Toolongforanameplate", 42, "kaelen"]},
                           {"tiers": ["galactic"], "values": ["Vorra"]}],
                 "epithets": [{"text": "the Grim", "boon": "hp_pool"},
                              {"text": "Grim", "boon": "hp_pool"},
                              {"text": "the Broken", "boon": "stat_focus:hp"},
                              {"text": "the Heavy", "boon": "explode"},
                              {"text": "the Odd", "types": ["cosmic"], "boon": "hp_pool"},
                              {"text": "the Weightless", "weight": 0, "boon": "hp_pool"},
                              {"text": "the grim", "boon": "hp_pool"},
                              "not an object"]}
                """, warnings);
        assertEquals(List.of("Kaelen", "Vorra"), pools.names().stream().map(RenownPools.NameEntry::name).toList());
        assertEquals(List.of("the Grim"), pools.epithets().stream().map(RenownPools.EpithetEntry::text).toList());
        // One per bad name, one for the bad tier, one per bad epithet.
        assertEquals(5 + 1 + 7, warnings.size(), String.join("\n", warnings));
        // An unknown tier is ignored rather than dropping the group, so Vorra falls back to every tier.
        assertEquals(RaidRarityTier.values().length, pools.names().get(1).tiers().size());
    }

    @Test
    @DisplayName("nothing to draw from yields no title rather than an exception")
    void emptyPoolsDrawNothing() {
        SplittableRandom random = new SplittableRandom(1);
        assertEquals(Optional.empty(), RenownPools.EMPTY.draw(RaidRarityTier.STARTER, List.of(), random));
        RenownPools namesOnly = parse("{\"names\": {\"values\": [\"Kaelen\"]}}", new ArrayList<>());
        assertEquals(Optional.empty(), namesOnly.draw(RaidRarityTier.STARTER, List.of(), random));
        RenownPools typedOnly = parse("""
                {"names": {"values": ["Kaelen"]}, "epithets": [{"text": "the Drowned", "types": ["water"], "boon": "hp_pool"}]}
                """, new ArrayList<>());
        assertEquals(Optional.empty(), typedOnly.draw(RaidRarityTier.STARTER, List.of("fire"), random));
    }

    @Test
    @DisplayName("every boon survives its own encoding, which is also how a boss's tag stores it")
    void boonCodecRoundTrips() {
        List<RenownBoon> boons = new ArrayList<>(List.of(RenownBoon.NONE, RenownBoon.HP_POOL));
        for (String stat : RenownBoon.FOCUS_STATS) boons.add(RenownBoon.statFocus(stat));
        for (RenownBoon boon : boons) {
            assertEquals(Optional.of(boon), RenownBoon.decode(boon.encode()));
        }
        assertEquals(Optional.of(RenownBoon.statFocus("defence")), RenownBoon.decode("stat_focus:defense"));
        assertEquals(Optional.empty(), RenownBoon.decode("stat_focus:hp"));
        assertEquals(Optional.empty(), RenownBoon.decode("stat_focus:"));
        assertEquals(Optional.empty(), RenownBoon.decode(null));
    }

    @Test
    @DisplayName("a title splits back into the name and epithet it was built from")
    void titlesSplit() {
        RaidRenown renown = new RaidRenown("Kaelen", "the Relentless", RenownBoon.HP_POOL);
        assertEquals("Kaelen, the Relentless", renown.title());
        String[] parts = RaidRenown.splitTitle(renown.title());
        assertEquals("Kaelen", parts[0]);
        assertEquals("the Relentless", parts[1]);
        assertEquals(null, RaidRenown.splitTitle("")[0]);
        assertEquals(null, RaidRenown.splitTitle(null)[0]);
        assertNotEquals(null, new RaidRenown("Vorra", "the Two Words", RenownBoon.NONE));
    }
}
