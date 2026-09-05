package com.cobbleraids.spawn;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Persistent scoreboard-tag marker inspired by the reference boss mod, with no runtime dependency on it. */
public final class RaidBossEntityMarker {
    private static final String ROOT = "cobbleraids_raid_boss";
    private static final String DEFINITION_PREFIX = "cobbleraids_definition=";
    private static final String NATURAL = "cobbleraids_natural_spawn";

    private RaidBossEntityMarker() {}

    public static void mark(PokemonEntity entity, ResourceLocation definitionId) {
        entity.addTag(ROOT);
        for (String tag : List.copyOf(entity.getTags())) {
            if (tag.startsWith(DEFINITION_PREFIX)) entity.removeTag(tag);
        }
        entity.addTag(DEFINITION_PREFIX + definitionId);
    }

    public static void markNatural(PokemonEntity entity) {
        if (entity != null) entity.addTag(NATURAL);
    }

    public static boolean isNatural(PokemonEntity entity) {
        return entity != null && entity.getTags().contains(NATURAL);
    }

    public static boolean isRaidBoss(PokemonEntity entity) {
        return entity != null && entity.getTags().contains(ROOT);
    }

    public static Optional<ResourceLocation> definitionId(PokemonEntity entity) {
        if (!isRaidBoss(entity)) return Optional.empty();
        for (String tag : entity.getTags()) {
            if (!tag.startsWith(DEFINITION_PREFIX)) continue;
            try { return Optional.of(ResourceLocation.parse(tag.substring(DEFINITION_PREFIX.length()))); }
            catch (RuntimeException ignored) { return Optional.empty(); }
        }
        return Optional.empty();
    }
}
