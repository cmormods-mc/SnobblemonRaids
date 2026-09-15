package com.cobbleraids.renown;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.List;
import java.util.Optional;

/**
 * Remembers a boss's renown on the entity itself, as scoreboard tags beside RaidBossEntityMarker's.
 *
 * <p>Tags for the same reason the failed-attempt counter uses them: the title then lives exactly as
 * long as the boss, survives chunk unloads and restarts, and needs no cleanup hook anywhere. The
 * rolled values are stored rather than a pointer into the word lists -- see RaidRenown.
 */
public final class RaidRenownMarker {
    private static final String NAME_PREFIX = "cobbleraids_renown_name=";
    private static final String EPITHET_PREFIX = "cobbleraids_renown_epithet=";
    private static final String BOON_PREFIX = "cobbleraids_renown_boon=";

    private RaidRenownMarker() {}

    public static void mark(PokemonEntity entity, RaidRenown renown) {
        clear(entity);
        entity.addTag(NAME_PREFIX + renown.name());
        entity.addTag(EPITHET_PREFIX + renown.epithet());
        entity.addTag(BOON_PREFIX + renown.boon().encode());
    }

    /** Empty for an ordinary boss, and for tags that no longer form a valid title. */
    public static Optional<RaidRenown> read(PokemonEntity entity) {
        if (entity == null) return Optional.empty();
        String name = null, epithet = null, boon = null;
        for (String tag : entity.getTags()) {
            if (tag.startsWith(NAME_PREFIX)) name = tag.substring(NAME_PREFIX.length());
            else if (tag.startsWith(EPITHET_PREFIX)) epithet = tag.substring(EPITHET_PREFIX.length());
            else if (tag.startsWith(BOON_PREFIX)) boon = tag.substring(BOON_PREFIX.length());
        }
        if (name == null || epithet == null || boon == null) return Optional.empty();
        Optional<RenownBoon> decoded = RenownBoon.decode(boon);
        if (decoded.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new RaidRenown(name, epithet, decoded.get()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static void clear(PokemonEntity entity) {
        for (String tag : List.copyOf(entity.getTags())) {
            if (tag.startsWith(NAME_PREFIX) || tag.startsWith(EPITHET_PREFIX) || tag.startsWith(BOON_PREFIX)) {
                entity.removeTag(tag);
            }
        }
    }
}
