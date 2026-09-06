package com.cobbleraids.spawn;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import java.util.Objects;
import kotlin.Unit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Creates a real Cobblemon PokemonEntity in the normal world for a raid definition. */
public final class RaidBossSpawner {
    private RaidBossSpawner() {}

    public static PokemonEntity spawnAt(ServerLevel level, Vec3 position, RaidDefinition definition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(definition, "definition");

        Species species = PokemonSpecies.getByIdentifier(definition.species());
        if (species == null) throw new IllegalArgumentException("Unknown Cobblemon species: " + definition.species());

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(definition.level());
        pokemon.initializeMoveset(false);
        pokemon.setCurrentHealth(pokemon.getMaxHealth());
        UncatchableProperty.INSTANCE.uncatchable().apply(pokemon);

        PokemonEntity entity = pokemon.sendOut(level, position, null, spawned -> {
            RaidBossEntityMarker.mark(spawned, definition.id());
            RaidBossEntityMarker.markSpawnTime(spawned, level.getGameTime());
            spawned.setPersistenceRequired();
            spawned.setCountsTowardsSpawnCap(false);
            spawned.setInvulnerable(true);
            spawned.setCustomName(RaidTierPresentation.styledName(definition.rarityTier(), spawned.getPokemon().getSpecies().getTranslatedName()));
            spawned.setCustomNameVisible(true);
            return Unit.INSTANCE;
        });
        if (entity == null) throw new IllegalStateException("Cobblemon did not create a PokemonEntity for " + definition.id());
        RaidBossGlowService.register(entity, level);
        return entity;
    }
}
