package com.cobbleraids.spawn;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Finding a tracked raid boss again, and asking whether anyone is standing near it.
 *
 * <p>Three services track bosses by id and dimension rather than by reference -- the spawn
 * scheduler, the glow service and the consistency audit -- and each had grown its own identical copy
 * of this lookup. Sharing it matters beyond tidiness, because the contract below is the one this
 * codebase keeps getting wrong.
 *
 * <p><b>A null return means "not loaded", never "gone".</b> Minecraft cannot distinguish an entity
 * in an unloaded chunk from one that no longer exists, so a failed lookup is not evidence of death.
 * Treating it as such is what leaked persistent bosses before Phase 32, and what silently stopped
 * bosses glowing after their first chunk unload. Callers must keep tracking an entry that does not
 * resolve and wait for it to come back.
 */
public final class RaidBossLookup {

    private RaidBossLookup() {}

    /**
     * The boss, or null if its dimension or chunk is not loaded.
     *
     * <p>A dimension that no longer exists also returns null: a dimension-managing mod can close a
     * ServerLevel outright, and a boss there can never resolve again.
     */
    public static PokemonEntity resolve(MinecraftServer server, UUID bossId, ResourceLocation dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return null;
        return level.getEntity(bossId) instanceof PokemonEntity pokemon ? pokemon : null;
    }

    /**
     * Whether a living, non-spectating player is within {@code radius} of the boss.
     *
     * <p>Walks the whole player list rather than the boss's level, because the level check is one
     * reference comparison and this is called at most once a second per tracked boss.
     */
    public static boolean hasNearbyPlayer(MinecraftServer server, PokemonEntity boss, double radius) {
        double radiusSqr = radius * radius;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == boss.level() && player.isAlive() && !player.isSpectator()
                    && player.distanceToSqr(boss) <= radiusSqr) return true;
        }
        return false;
    }
}
