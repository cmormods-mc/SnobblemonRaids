package com.cobbleraids.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Boss traits are read while a datapack loads, and RaidDefinitionRegistry rethrows anything that
 * escapes -- which aborts the load of all 130 definitions. So the property that matters most here
 * is that a malformed trait block degrades to a warning and a dropped field, never an exception.
 */
class RaidBossTraitsTest {

    private static RaidBossTraits parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return RaidBossTraits.fromJson(root, "cobbleraids:test");
    }

    @Nested
    @DisplayName("absent or empty")
    class Absent {

        @Test
        @DisplayName("a definition with no traits block is a no-op")
        void noBlockIsNone() {
            RaidBossTraits traits = parse("{\"species\": \"cobblemon:blastoise\"}");

            assertTrue(traits.isEmpty());
            assertEquals(RaidBossTraits.NONE, traits);
        }

        @Test
        @DisplayName("an empty traits block is also a no-op")
        void emptyBlockIsNone() {
            assertTrue(parse("{\"traits\": {}}").isEmpty());
        }

        @Test
        @DisplayName("a traits block of the wrong type is ignored rather than fatal")
        void wrongTypeIsIgnored() {
            // A definition load that throws takes every other raid down with it.
            assertTrue(parse("{\"traits\": \"adamant\"}").isEmpty());
            assertTrue(parse("{\"traits\": []}").isEmpty());
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads every supported field")
        void readsAllFields() {
            RaidBossTraits traits = parse("""
                    {"traits": {
                      "nature": "Adamant",
                      "ability": "Torrent",
                      "ivs": {"attack": 31, "speed": 30},
                      "evs": {"attack": 252},
                      "held_item": "cobblemon:expert_belt",
                      "gender": "male",
                      "tera_type": "water",
                      "form": "gmax"
                    }}""");

            assertFalse(traits.isEmpty());
            assertEquals("adamant", traits.nature(), "ids are normalised to lower case");
            assertEquals("torrent", traits.ability());
            assertEquals(31, traits.ivs().get("attack"));
            assertEquals(30, traits.ivs().get("speed"));
            assertEquals(252, traits.evs().get("attack"));
            assertEquals("cobblemon:expert_belt", traits.heldItem());
            assertEquals("male", traits.gender());
            assertEquals("water", traits.teraType());
            assertEquals("gmax", traits.form());
        }

        @Test
        @DisplayName("accepts American stat spellings")
        void acceptsStatAliases() {
            RaidBossTraits traits = parse(
                    "{\"traits\": {\"ivs\": {\"defense\": 20, \"special_defense\": 25}}}");

            assertEquals(20, traits.ivs().get("defence"));
            assertEquals(25, traits.ivs().get("special_defence"));
        }

        @Test
        @DisplayName("an unknown stat is dropped, the rest of the block survives")
        void unknownStatIsDropped() {
            RaidBossTraits traits = parse(
                    "{\"traits\": {\"nature\": \"timid\", \"ivs\": {\"luck\": 31, \"speed\": 31}}}");

            assertEquals("timid", traits.nature());
            assertEquals(1, traits.ivs().size());
            assertEquals(31, traits.ivs().get("speed"));
        }

        @Test
        @DisplayName("out-of-range IVs and EVs are dropped rather than clamped silently")
        void outOfRangeValuesAreDropped() {
            RaidBossTraits traits = parse(
                    "{\"traits\": {\"ivs\": {\"attack\": 99, \"speed\": 31}, \"evs\": {\"hp\": 999}}}");

            assertEquals(1, traits.ivs().size(), "only the legal IV should survive");
            assertEquals(31, traits.ivs().get("speed"));
            assertTrue(traits.evs().isEmpty());
        }

        @Test
        @DisplayName("a non-numeric stat value does not abort the load")
        void nonNumericStatIsDropped() {
            assertTrue(parse("{\"traits\": {\"ivs\": {\"attack\": \"max\"}}}").ivs().isEmpty());
        }

        @Test
        @DisplayName("blank and null strings read as unset")
        void blankStringsAreUnset() {
            RaidBossTraits traits = parse("{\"traits\": {\"nature\": \"  \", \"ability\": null}}");

            assertNull(traits.nature());
            assertNull(traits.ability());
            assertTrue(traits.isEmpty());
        }

        @Test
        @DisplayName("stat maps are defensively copied")
        void statMapsAreImmutable() {
            RaidBossTraits traits = parse("{\"traits\": {\"ivs\": {\"speed\": 31}}}");

            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> traits.ivs().put("attack", 31));
        }
    }

    @Nested
    @DisplayName("held-item policy")
    class HeldItems {

        @Test
        @DisplayName("allowed items pass")
        void allowedItemsPass() {
            assertNull(RaidBossTraits.heldItemRejection("cobblemon:expert_belt"));
            assertNull(RaidBossTraits.heldItemRejection("cobblemon:rocky_helmet"));
            assertNull(RaidBossTraits.heldItemRejection("minecraft:charcoal"));
            assertNull(RaidBossTraits.heldItemRejection(null), "unset is not a rejection");
        }

        @Test
        @DisplayName("items that fight the pinned boss HP are refused with a reason")
        void hpInteractingItemsRefused() {
            for (String item : new String[] {
                    "cobblemon:focus_sash", "cobblemon:focus_band",
                    "cobblemon:leftovers", "cobblemon:shell_bell", "cobblemon:life_orb"}) {
                assertNotNull(RaidBossTraits.heldItemRejection(item), item + " must be refused");
            }
        }

        @Test
        @DisplayName("Power Herb stays refused, because it is what makes charge moves fair here")
        void powerHerbRefused() {
            String reason = RaidBossTraits.heldItemRejection("cobblemon:power_herb");

            assertNotNull(reason);
            assertTrue(reason.contains("charge"), reason);
        }

        @Test
        @DisplayName("switch-forcing items are refused on a one-Pokemon boss side")
        void switchForcingItemsRefused() {
            assertNotNull(RaidBossTraits.heldItemRejection("cobblemon:red_card"));
            assertNotNull(RaidBossTraits.heldItemRejection("cobblemon:eject_button"));
        }

        @Test
        @DisplayName("an unrecognised item is refused, not waved through")
        void unknownItemRefused() {
            assertNotNull(RaidBossTraits.heldItemRejection("minecraft:diamond_sword"));
        }

        @Test
        @DisplayName("a refused item is dropped but the rest of the traits are kept")
        void refusedItemDoesNotDiscardTheBlock() {
            RaidBossTraits traits = parse(
                    "{\"traits\": {\"nature\": \"modest\", \"held_item\": \"cobblemon:focus_sash\"}}");

            assertNull(traits.heldItem());
            assertEquals("modest", traits.nature());
        }
    }

    @Nested
    @DisplayName("IV jitter")
    class Jitter {

        @Test
        @DisplayName("a spread of 0 pins the value exactly")
        void zeroSpreadPins() {
            Random random = new Random(1L);
            for (int i = 0; i < 100; i++) {
                assertEquals(31, RaidBossTraits.jitterIv(31, 0, random));
            }
        }

        @Test
        @DisplayName("results stay within the spread and always remain legal IVs")
        void staysInBandAndLegal() {
            Random random = new Random(20260908L);
            for (int i = 0; i < 20_000; i++) {
                int rolled = RaidBossTraits.jitterIv(15, 3, random);
                assertTrue(rolled >= 12 && rolled <= 18, "outside the band: " + rolled);
            }
        }

        @Test
        @DisplayName("jitter cannot push an IV outside 0..31")
        void clampsAtTheEdges() {
            Random random = new Random(7L);
            for (int i = 0; i < 20_000; i++) {
                assertTrue(RaidBossTraits.jitterIv(31, 5, random) <= 31);
                assertTrue(RaidBossTraits.jitterIv(0, 5, random) >= 0);
            }
        }

        @Test
        @DisplayName("a non-zero spread actually varies the value")
        void actuallyVaries() {
            // The point of the feature: repeat raids should not be byte-identical.
            Random random = new Random(99L);
            boolean moved = false;
            for (int i = 0; i < 200 && !moved; i++) {
                moved = RaidBossTraits.jitterIv(15, 3, random) != 15;
            }
            assertTrue(moved, "jitter never changed the pinned value");
        }
    }
}
