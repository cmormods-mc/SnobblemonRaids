package com.cobbleraids.catching;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/**
 * Captures a defeated raid boss into its victors' personal shop, one slot per species per player.
 *
 * <p>Copies the boss ({@code boss.clone(true, registries)}) rather than touching the live entity,
 * which is still wired into the finishing battle and about to be discarded. See
 * {@link DefeatedBossSnapshots} for why the copy is kept as full Pokemon NBT rather than a handful
 * of primitive fields.
 */
public final class BossSnapshotService {

    private BossSnapshotService() {}

    public static void snapshot(MinecraftServer server, UUID playerId, RaidRarityTier tier, Pokemon bossPokemon) {
        Pokemon copy = bossPokemon.clone(true, server.registryAccess());
        // Uncatchable belongs to the boss standing in the world, not to a snapshot a player will
        // eventually buy back.
        UncatchableProperty.INSTANCE.catchable().apply(copy);

        int ivPercent = percentOf(copy.getIvs().total(), 186);
        int evPercent = percentOf(copy.getEvs().total(), 510);
        CompoundTag tag = copy.saveToNBT(server.registryAccess(), new CompoundTag());

        DefeatedBossSnapshots.put(server, playerId, new BossSnapshot(
                copy.getSpecies().getResourceIdentifier(), copy.getLevel(), copy.getShiny(),
                ivPercent, evPercent, tier, tag, System.currentTimeMillis()));
    }

    /**
     * The level a bought-back boss arrives at: the level its raid definition asks for, never the level
     * it was raised to for the party that beat it.
     *
     * <p>A boss scaled to a level-100 party is a level-100 Pokemon in the snapshot, and buying that
     * back for the price of a level-25 starter would hand over a free level 100. The base level is
     * looked up by species rather than stored in the snapshot, so a snapshot saved before this rule
     * existed is capped too. A species with no definition (a definition since removed) keeps the
     * level it was saved at.
     */
    public static int buyBackLevel(BossSnapshot snapshot) {
        List<Integer> baseLevels = new ArrayList<>();
        if (snapshot.species().getNamespace().equals("cobblemon")) {
            for (RaidDefinition definition : RaidDefinitionRegistry.findBySpeciesName(snapshot.species().getPath())) {
                baseLevels.add(definition.level());
            }
        }
        return capLevel(snapshot.level(), baseLevels);
    }

    /**
     * {@code savedLevel}, lowered to the lowest of {@code baseLevels}; unchanged when there are none.
     * The lowest, because a species with two definitions should buy back as its gentlest self, not
     * as whichever the registry happened to list first. Never raises a level.
     */
    public static int capLevel(int savedLevel, Collection<Integer> baseLevels) {
        int cap = Integer.MAX_VALUE;
        for (Integer level : baseLevels) {
            if (level != null && level > 0) cap = Math.min(cap, level);
        }
        return cap == Integer.MAX_VALUE ? savedLevel : Math.min(savedLevel, cap);
    }

    /**
     * Rounded to the nearest whole percent. {@code total} is never negative; Cobblemon enforces
     * that. Public because {@code PersonalBossShopService.reroll} needs the exact same formula to
     * recompute a snapshot's display scalars after mutating its IVs/EVs.
     */
    public static int percentOf(int total, int max) {
        return Math.round(total * 100.0f / max);
    }
}
