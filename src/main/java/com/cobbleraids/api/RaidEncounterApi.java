package com.cobbleraids.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Stable addon-facing service for CobbleRaids boss encounters.
 *
 * <p>Implementations own boss creation, shared-battle startup, withdrawal, cleanup, and Cobblemon
 * battle invariants. Addons own their surrounding game mode and rewards.
 */
public interface RaidEncounterApi {
    List<RaidBossDescriptor> bosses();

    Optional<RaidBossDescriptor> boss(ResourceLocation definitionId);

    /**
     * Starts a single-use addon-managed boss encounter.
     *
     * @param healthMultiplier multiplier applied after CobbleRaids' normal participant health scaling
     * @param completion invoked once after CobbleRaids has completed terminal cleanup
     */
    RaidEncounterHandle start(
            ServerLevel level,
            Vec3 bossPosition,
            Collection<ServerPlayer> participants,
            ResourceLocation definitionId,
            double healthMultiplier,
            Consumer<RaidEncounterResult> completion
    );

    /** Withdraws one active participant while allowing the remaining participants to continue. */
    boolean withdraw(RaidEncounterHandle handle, ServerPlayer player);

    /** Terminates the encounter without treating it as a player defeat. */
    boolean abort(RaidEncounterHandle handle);
}
