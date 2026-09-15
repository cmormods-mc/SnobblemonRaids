package com.cobbleraids.renown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Properties of the word lists this jar ships, re-derived from the files on every run.
 *
 * <p>Checked as properties rather than as a list of expected entries, so adding a name cannot break
 * this and a real regression -- a tier or a type that can no longer produce a title -- cannot pass.
 */
class ShippedRenownListsTest {

    private static final Path DIRECTORY = Path.of("src/main/resources/data/cobbleraids/renown");

    private static RenownPools load(List<String> warnings) throws IOException {
        Map<String, JsonObject> files = new TreeMap<>();
        try (Stream<Path> paths = Files.list(DIRECTORY)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                files.put(path.getFileName().toString(),
                        JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        return RenownPools.parse(files, warnings::add);
    }

    @Test
    @DisplayName("the shipped lists load without a single warning")
    void loadsClean() throws IOException {
        List<String> warnings = new ArrayList<>();
        RenownPools pools = load(warnings);
        assertEquals(List.of(), warnings);
        assertFalse(pools.names().isEmpty());
        assertFalse(pools.epithets().isEmpty());
    }

    @Test
    @DisplayName("every tier can title a boss of any single type, and one with no types at all")
    void everyBossCanBeTitled() throws IOException {
        RenownPools pools = load(new ArrayList<>());
        SplittableRandom random = new SplittableRandom(42);
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            assertTrue(pools.draw(tier, List.of(), random).isPresent(), tier + " with no types");
            for (String type : RenownPools.TYPES) {
                assertTrue(pools.draw(tier, List.of(type), random).isPresent(), tier + " " + type);
            }
        }
    }

    @Test
    @DisplayName("every tier offers both kinds of boon without needing a type")
    void everyTierHasBothBoons() throws IOException {
        RenownPools pools = load(new ArrayList<>());
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            for (RenownBoon.Kind kind : List.of(RenownBoon.Kind.HP_POOL, RenownBoon.Kind.STAT_FOCUS)) {
                assertTrue(pools.epithets().stream().anyMatch(e -> e.types().isEmpty()
                                && e.tiers().contains(tier) && e.boon().kind() == kind),
                        tier + " has no untyped " + kind + " epithet");
            }
        }
    }
}
