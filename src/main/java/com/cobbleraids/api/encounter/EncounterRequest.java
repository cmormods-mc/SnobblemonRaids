package com.cobbleraids.api.encounter;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Everything CobbleRaids needs to start an owned encounter, decided by the caller.
 *
 * @param owner        who owns the encounter, e.g. {@code cobbletowers:run}; recorded on the boss
 * @param encounterId  the caller's id for this encounter, used by every later call and callback
 * @param players      the participants, 1 to {@link #MAX_PLAYERS}, all online and in {@code level}
 * @param definitionId the raid definition that supplies species, moves, traits and base health
 * @param level        where the boss appears
 * @param position     where in {@code level} the boss appears
 * @param bossLevel    the boss's final level. Applied as given: CobbleRaids does not rescale it
 * @param maxHealth    the shared health pool; when empty, derived from the definition, the number of
 *                     players and {@code bossLevel} exactly as an ordinary raid derives it
 * @param policy       which side effects the encounter has
 */
public record EncounterRequest(
        ResourceLocation owner,
        UUID encounterId,
        List<ServerPlayer> players,
        ResourceLocation definitionId,
        ServerLevel level,
        Vec3 position,
        int bossLevel,
        OptionalLong maxHealth,
        EncounterPolicy policy) {

    /** The most players one encounter can hold: the largest raid the Showdown integration is validated for. */
    public static final int MAX_PLAYERS = 4;

    public EncounterRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(maxHealth, "maxHealth");
        Objects.requireNonNull(policy, "policy");
        validateShape(players.size(), bossLevel, maxHealth);
        players = List.copyOf(players);  // also rejects a null element
        HashSet<UUID> distinct = new HashSet<>();
        for (ServerPlayer player : players) {
            if (!distinct.add(player.getUUID())) {
                throw new IllegalArgumentException("player " + player.getGameProfile().getName() + " is listed twice");
            }
        }
    }

    /** The checks that need no live objects, separate so they can be tested without a server. */
    static void validateShape(int playerCount, int bossLevel, OptionalLong maxHealth) {
        if (playerCount < 1 || playerCount > MAX_PLAYERS) {
            throw new IllegalArgumentException("an encounter needs 1 to " + MAX_PLAYERS + " players, got " + playerCount);
        }
        if (bossLevel < 1) throw new IllegalArgumentException("bossLevel must be at least 1, got " + bossLevel);
        if (maxHealth.isPresent() && maxHealth.getAsLong() <= 0L) {
            throw new IllegalArgumentException("maxHealth must be positive when given, got " + maxHealth.getAsLong());
        }
    }
}
