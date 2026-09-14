package com.cobbleraids.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Opaque stable identity for one addon-managed raid encounter.
 *
 * <p>The handle deliberately contains no PokemonBattle, PokemonEntity or RaidSession reference.
 */
public record RaidEncounterHandle(UUID encounterId, UUID battleId, ResourceLocation definitionId) {
    public RaidEncounterHandle {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(definitionId, "definitionId");
    }
}
