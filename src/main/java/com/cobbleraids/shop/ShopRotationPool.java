package com.cobbleraids.shop;

import com.cobblemon.mod.common.api.abilities.CommonAbility;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Turns Cobblemon's species registry into the candidates the rotation picks from.
 *
 * <p>Built once and kept: a thousand species is cheap to read but there is no reason to read them
 * on every page open. {@link #invalidate()} drops it on a data pack reload, because that is the
 * moment species and their labels can change.
 */
public final class ShopRotationPool {

    private static volatile List<ShopRotation.Candidate> cached;
    private static volatile List<String> cachedFor;

    private ShopRotationPool() {}

    public static void invalidate() {
        cached = null;
        cachedFor = null;
    }

    /** The pool for these excluded labels. A change of labels (a shop.json reload) rebuilds it. */
    public static List<ShopRotation.Candidate> get(List<String> excludedLabels) {
        List<ShopRotation.Candidate> pool = cached;
        if (pool != null && excludedLabels.equals(cachedFor)) return pool;
        synchronized (ShopRotationPool.class) {
            if (cached != null && excludedLabels.equals(cachedFor)) return cached;
            cached = build(Set.copyOf(excludedLabels));
            cachedFor = List.copyOf(excludedLabels);
            return cached;
        }
    }

    private static List<ShopRotation.Candidate> build(Set<String> excluded) {
        List<ShopRotation.Candidate> pool = new ArrayList<>();
        for (Species species : PokemonSpecies.getImplemented()) {
            if (species.getLabels().stream().anyMatch(excluded::contains)) continue;
            ResourceLocation id = species.getResourceIdentifier();
            // Cobblemon's own species are named bare, which is what every shop entry already does;
            // an add-on's keep their namespace so two mods' "eevee" cannot be confused.
            String speciesId = id.getNamespace().equals("cobblemon") ? id.getPath() : id.toString();
            int total = species.getBaseStats().values().stream().mapToInt(Integer::intValue).sum();
            List<String> abilities = new ArrayList<>();
            for (PotentialAbility potential : species.getAbilities()) {
                if (potential instanceof CommonAbility) abilities.add(potential.getTemplate().getName());
            }
            pool.add(new ShopRotation.Candidate(speciesId, species.getCatchRate(), total,
                    List.copyOf(abilities), species.getMaleRatio()));
        }
        return List.copyOf(pool);
    }
}
