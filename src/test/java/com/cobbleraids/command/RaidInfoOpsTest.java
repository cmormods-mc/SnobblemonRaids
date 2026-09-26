package com.cobbleraids.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbleraids.config.RaidBossTraits;
import com.cobbleraids.config.RaidDefinition;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * The plain-English formatters {@code /cobbleraids info} and {@code /cobbleraids debug definition}
 * share. Each one is a pure function of a {@code RaidDefinition.Spawn} or {@code RaidBossTraits}, so
 * this builds those directly rather than a whole {@code RaidDefinition}, which carries several
 * unrelated sub-records these formatters never read.
 */
class RaidInfoOpsTest {

    private static RaidDefinition.Spawn spawn(List<ResourceLocation> dimensions, List<ResourceLocation> biomes,
                                               List<ResourceLocation> biomeTags, List<RaidDefinition.SpawnTime> times) {
        return new RaidDefinition.Spawn(true, 100, dimensions, biomes, biomeTags, times, 60, 300, 3600, 1);
    }

    @Test
    void habitatsPrefersBiomeTagsOverPlainBiomes() {
        RaidDefinition.Spawn spawn = spawn(List.of(),
                List.of(ResourceLocation.parse("minecraft:desert")),
                List.of(ResourceLocation.parse("cobbleraids:raid_types/fire")),
                List.of());

        assertEquals("fire", RaidInfoOps.habitats(spawn));
    }

    @Test
    void habitatsFallsBackToPlainBiomesWhenThereAreNoTags() {
        RaidDefinition.Spawn spawn = spawn(List.of(),
                List.of(ResourceLocation.parse("minecraft:desert"), ResourceLocation.parse("minecraft:badlands")),
                List.of(),
                List.of());

        assertEquals("desert, badlands", RaidInfoOps.habitats(spawn));
    }

    @Test
    void habitatsIsAnyBiomeWhenNeitherIsGiven() {
        assertEquals("any biome", RaidInfoOps.habitats(spawn(List.of(), List.of(), List.of(), List.of())));
    }

    @Test
    void timesIsAnyTimeWhenAllDayIsPresent() {
        RaidDefinition.Spawn spawn = spawn(List.of(), List.of(), List.of(),
                List.of(RaidDefinition.SpawnTime.ALL_DAY));

        assertEquals("any time", RaidInfoOps.times(spawn));
    }

    @Test
    void timesListsEachTimeLowercasedWithUnderscoresReplaced() {
        RaidDefinition.Spawn spawn = spawn(List.of(), List.of(), List.of(),
                List.of(RaidDefinition.SpawnTime.EARLY_MORNING, RaidDefinition.SpawnTime.NIGHT));

        assertEquals("early morning, night", RaidInfoOps.times(spawn));
    }

    @Test
    void dimensionsIsAnyDimensionWhenEmpty() {
        assertEquals("any dimension", RaidInfoOps.dimensions(spawn(List.of(), List.of(), List.of(), List.of())));
    }

    @Test
    void dimensionsShortensCobbleraidsAndMinecraftNamespacesButNotOthers() {
        RaidDefinition.Spawn spawn = spawn(
                List.of(ResourceLocation.parse("minecraft:the_nether"),
                        ResourceLocation.parse("othermod:custom_dimension")),
                List.of(), List.of(), List.of());

        assertEquals("the_nether, othermod:custom_dimension", RaidInfoOps.dimensions(spawn));
    }

    @Test
    void describeTraitsIsEmptyForNone() {
        assertEquals("", RaidInfoOps.describeTraits(RaidBossTraits.NONE));
    }

    @Test
    void describeTraitsJoinsEveryPresentFieldInDeclarationOrder() {
        RaidBossTraits traits = new RaidBossTraits("adamant", "intimidate", Map.of("attack", 31),
                Map.of("hp", 252), "cobblemon:life_orb", "male", "fire", "alolan");

        assertEquals("adamant · intimidate · male · form alolan · tera fire · cobblemon:life_orb"
                        + " · ivs {attack=31} · evs {hp=252}",
                RaidInfoOps.describeTraits(traits));
    }
}
