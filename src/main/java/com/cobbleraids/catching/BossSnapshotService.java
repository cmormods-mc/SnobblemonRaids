package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/**
 * Captures a defeated raid boss into its victors' personal shop, one slot per species per player.
 *
 * <p>Copies the boss the same way {@link RaidCatchService#award} already does --
 * {@code boss.clone(true, registries)} -- rather than touching the live entity, which is still
 * wired into the finishing battle and about to be discarded. See {@link DefeatedBossSnapshots} for
 * why the copy is kept as full Pokemon NBT rather than a handful of primitive fields.
 */
public final class BossSnapshotService {

    private BossSnapshotService() {}

    public static void snapshot(MinecraftServer server, UUID playerId, RaidRarityTier tier, Pokemon bossPokemon) {
        Pokemon copy = bossPokemon.clone(true, server.registryAccess());
        // Uncatchable belongs to the boss standing in the world, not to a snapshot a player will
        // eventually buy back -- same reasoning RaidCatchService.award applies to its own copy.
        UncatchableProperty.INSTANCE.catchable().apply(copy);

        int ivPercent = percentOf(copy.getIvs().total(), 186);
        int evPercent = percentOf(copy.getEvs().total(), 510);
        CompoundTag tag = copy.saveToNBT(server.registryAccess(), new CompoundTag());

        DefeatedBossSnapshots.put(server, playerId, new BossSnapshot(
                copy.getSpecies().getResourceIdentifier(), copy.getLevel(), copy.getShiny(),
                ivPercent, evPercent, tier, tag, System.currentTimeMillis()));
    }

    /** Rounded to the nearest whole percent. {@code total} is never negative; Cobblemon enforces that. */
    static int percentOf(int total, int max) {
        return Math.round(total * 100.0f / max);
    }
}
