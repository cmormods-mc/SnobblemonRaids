package com.cobbleraids.spawn;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.resources.ResourceLocation;

/** Persistent scoreboard-tag marker inspired by the reference boss mod, with no runtime dependency on it. */
public final class RaidBossEntityMarker {
    private static final String ROOT = "cobbleraids_raid_boss";
    private static final String DEFINITION_PREFIX = "cobbleraids_definition=";
    private static final String NATURAL = "cobbleraids_natural_spawn";
    private static final String SPAWN_TICK_PREFIX = "cobbleraids_spawn_tick=";
    private static final String ATTEMPTS_PREFIX = "cobbleraids_failed_attempts=";
    private static final String OWNER_PREFIX = "cobbleraids_owner=";

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

    /**
     * Marks a boss as belonging to an encounter another mod owns (see CobbleRaidsEncounters).
     *
     * <p>An owned boss is never recruited by passers-by and never survives a restart. A tag rather
     * than a lookup for the same reason as the rest of this class: it has exactly the boss's lifetime,
     * so a boss found at boot is still recognisable after the encounter that owned it is long gone.
     */
    public static void markOwned(PokemonEntity entity, ResourceLocation owner) {
        for (String tag : List.copyOf(entity.getTags())) {
            if (tag.startsWith(OWNER_PREFIX)) entity.removeTag(tag);
        }
        entity.addTag(OWNER_PREFIX + owner);
    }

    public static boolean isOwned(PokemonEntity entity) {
        if (entity == null) return false;
        for (String tag : entity.getTags()) {
            if (tag.startsWith(OWNER_PREFIX)) return true;
        }
        return false;
    }

    /** Recorded once at spawn (natural or admin) so age can be reported without relying on
     *  scheduler tracking, which only exists for naturally spawned bosses. */
    public static void markSpawnTime(PokemonEntity entity, long gameTime) {
        for (String tag : List.copyOf(entity.getTags())) {
            if (tag.startsWith(SPAWN_TICK_PREFIX)) entity.removeTag(tag);
        }
        entity.addTag(SPAWN_TICK_PREFIX + gameTime);
    }

    public static OptionalLong spawnTick(PokemonEntity entity) {
        if (entity == null) return OptionalLong.empty();
        for (String tag : entity.getTags()) {
            if (!tag.startsWith(SPAWN_TICK_PREFIX)) continue;
            try { return OptionalLong.of(Long.parseLong(tag.substring(SPAWN_TICK_PREFIX.length()))); }
            catch (NumberFormatException ignored) { return OptionalLong.empty(); }
        }
        return OptionalLong.empty();
    }

    /**
     * How many raids against this boss have ended in defeat.
     *
     * <p>Held on the entity as a scoreboard tag, like everything else here, rather than in a static
     * map keyed by UUID. That is deliberate: the count then has exactly the lifetime of the boss it
     * describes. It survives a chunk unload and a restart, needs no cleanup hook on despawn, on
     * dimension close or at shutdown, and cannot leak an entry for an entity that no longer exists.
     */
    public static int failedAttempts(PokemonEntity entity) {
        if (entity == null) return 0;
        for (String tag : entity.getTags()) {
            if (!tag.startsWith(ATTEMPTS_PREFIX)) continue;
            try { return Math.max(0, Integer.parseInt(tag.substring(ATTEMPTS_PREFIX.length()))); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    /** Records one more defeat and returns the new total. */
    public static int recordFailedAttempt(PokemonEntity entity) {
        if (entity == null) return 0;
        int next = failedAttempts(entity) + 1;
        for (String tag : List.copyOf(entity.getTags())) {
            if (tag.startsWith(ATTEMPTS_PREFIX)) entity.removeTag(tag);
        }
        entity.addTag(ATTEMPTS_PREFIX + next);
        return next;
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
