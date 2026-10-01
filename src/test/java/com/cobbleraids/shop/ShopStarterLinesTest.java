package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShopStarterLinesTest {

    @Test
    @DisplayName("every generation's three starters are covered: 27 lines, three stages each")
    void twentySevenLines() {
        assertEquals(27, ShopStarterLines.lineCount());
        Set<String> names = new HashSet<>();
        for (String id : new String[] {"bulbasaur", "charmander", "squirtle", "chikorita", "cyndaquil", "totodile",
                "treecko", "torchic", "mudkip", "turtwig", "chimchar", "piplup", "snivy", "tepig", "oshawott",
                "chespin", "fennekin", "froakie", "rowlet", "litten", "popplio", "grookey", "scorbunny", "sobble",
                "sprigatito", "fuecoco", "quaxly"}) {
            assertEquals(ShopStarterLines.Stage.BASE, ShopStarterLines.stageOf(id).orElseThrow(), id);
            names.add(id);
        }
        assertEquals(27, names.size());
    }

    @Test
    @DisplayName("the stage follows the line: first form, middle form, fully evolved")
    void stagesFollowTheLine() {
        assertEquals(ShopStarterLines.Stage.MIDDLE, ShopStarterLines.stageOf("charmeleon").orElseThrow());
        assertEquals(ShopStarterLines.Stage.FINAL, ShopStarterLines.stageOf("charizard").orElseThrow());
        assertEquals(ShopStarterLines.Stage.FINAL, ShopStarterLines.stageOf("Quaquaval").orElseThrow());
        assertEquals(ShopStarterLines.Stage.MIDDLE, ShopStarterLines.stageOf("cobblemon:dewott").orElseThrow());
    }

    @Test
    @DisplayName("only Cobblemon's own species count, and anything else is no starter")
    void notEverySpeciesIsAStarter() {
        assertTrue(ShopStarterLines.stageOf("pikachu").isEmpty());
        assertTrue(ShopStarterLines.stageOf("eevee").isEmpty());
        assertTrue(ShopStarterLines.stageOf("somemod:charmander").isEmpty());
        assertTrue(ShopStarterLines.stageOf(null).isEmpty());
        assertTrue(ShopStarterLines.stageOf("").isEmpty());
    }
}
