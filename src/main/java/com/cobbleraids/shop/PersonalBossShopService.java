package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.BossSnapshot;
import com.cobbleraids.catching.BossSnapshotService;
import com.cobbleraids.catching.DefeatedBossSnapshots;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidBossTraits;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.pokemon.PokemonStatNames;
import com.cobbleraids.reward.points.RaidPointsStore;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Buying back, or rerolling, a boss from the player's own personal shop page.
 *
 * <p>A sibling to {@link ShopPurchaseService}, not a branch of it: a personal entry is never in
 * {@link ShopCatalogManager}'s catalogue, so nothing here reads from it, and the id it dispatches
 * on ({@code "personal:"}/{@code "reroll:"}-prefixed) is resolved against a single player's own
 * {@link DefeatedBossSnapshots}, not a shared, global table. Otherwise the same ordering
 * discipline as {@code ShopPurchaseService}'s own class doc states: everything that can fail
 * happens before any points are taken.
 */
public final class PersonalBossShopService {

    /** Cobblemon's six permanent stats, as {@link PokemonStatNames} and {@link RaidBossTraits} key them. */
    private static final List<String> STAT_KEYS =
            List.of("hp", "attack", "defence", "special_attack", "special_defence", "speed");

    private PersonalBossShopService() {}

    public static ShopPurchaseResult purchase(ServerPlayer player, ResourceLocation species) {
        BossSnapshot snapshot = DefeatedBossSnapshots.get(player.getUUID(), species);
        if (snapshot == null) return ShopPurchaseResult.UNKNOWN_ENTRY;

        Pokemon pokemon = deserialize(player, snapshot);
        if (pokemon == null) return ShopPurchaseResult.UNRESOLVED;
        pokemon.heal();

        int cost = CobbleRaidsConfigManager.get().personalBossShop().buyCostFor(snapshot.rarityTier());
        if (RaidPointsStore.balance(player.getUUID()) < cost) return ShopPurchaseResult.NOT_ENOUGH_POINTS;

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        boolean partyHasRoom = party.getFirstAvailablePosition() != null;
        // Asked before anything is spent, same reason ShopPurchaseService.givePokemon asks first:
        // there is then no purchase to unwind.
        if (!partyHasRoom && Cobblemon.INSTANCE.getStorage().getPC(player).getFirstAvailablePosition() == null) {
            return ShopPurchaseResult.NO_ROOM;
        }

        RaidPointsStore.spend(player.getServer(), player.getUUID(), cost);
        ShopPurchaseResult delivered;
        if (partyHasRoom && party.add(pokemon)) {
            delivered = ShopPurchaseResult.BOUGHT;
        } else if (Cobblemon.INSTANCE.getStorage().getPC(player).add(pokemon)) {
            delivered = ShopPurchaseResult.BOUGHT_TO_PC;
        } else {
            // Unreachable given the room check just above, the same residual case
            // ShopPurchaseService.givePokemon documents and logs loudly for rather than silently.
            RaidLog.error("Personal boss purchase of " + species + " by " + player.getGameProfile().getName()
                    + " charged the player but the Pokemon could not be added to either the party or the"
                    + " PC despite a free slot being confirmed beforehand.");
            delivered = ShopPurchaseResult.FAILED;
        }

        // The slot is spent by being bought, not by a purchase-window resetting -- removed only
        // once delivery actually succeeded, so a FAILED delivery (the unreachable case above)
        // leaves the snapshot in place rather than losing it along with the points already spent.
        if (delivered.success()) {
            DefeatedBossSnapshots.remove(player.getServer(), player.getUUID(), species);
        }
        // Two separate SavedData files changed together (points spent, slot removed); both need
        // their own explicit flush for the same reason ShopPurchaseService.settle flushes points --
        // a purchase reaching the player's inventory must not be able to desync from either record
        // across a crash before the next autosave.
        RaidPlayerRecords.flush(player.getServer());
        DefeatedBossSnapshots.flush(player.getServer());
        RaidLog.info("Personal shop: " + player.getGameProfile().getName() + " bought back " + species
                + " for " + cost + " RP");
        return delivered;
    }

    public static ShopPurchaseResult reroll(ServerPlayer player, ResourceLocation species) {
        BossSnapshot snapshot = DefeatedBossSnapshots.get(player.getUUID(), species);
        if (snapshot == null) return ShopPurchaseResult.UNKNOWN_ENTRY;

        Pokemon pokemon = deserialize(player, snapshot);
        if (pokemon == null) return ShopPurchaseResult.UNRESOLVED;

        CobbleRaidsConfig.PersonalBossShop config = CobbleRaidsConfigManager.get().personalBossShop();
        if (RaidPointsStore.balance(player.getUUID()) < config.rerollCost()) return ShopPurchaseResult.NOT_ENOUGH_POINTS;

        RaidPointsStore.spend(player.getServer(), player.getUUID(), config.rerollCost());
        applyReroll(pokemon);

        CompoundTag tag = pokemon.saveToNBT(player.registryAccess(), new CompoundTag());
        BossSnapshot updated = new BossSnapshot(species, snapshot.level(), snapshot.shiny(),
                BossSnapshotService.percentOf(pokemon.getIvs().total(), 186),
                BossSnapshotService.percentOf(pokemon.getEvs().total(), 510),
                snapshot.rarityTier(), tag, System.currentTimeMillis());
        DefeatedBossSnapshots.put(player.getServer(), player.getUUID(), updated);

        RaidPlayerRecords.flush(player.getServer());
        DefeatedBossSnapshots.flush(player.getServer());
        RaidLog.info("Personal shop: " + player.getGameProfile().getName() + " rerolled " + species
                + " for " + config.rerollCost() + " RP (now iv%=" + updated.ivPercent()
                + " ev%=" + updated.evPercent() + ")");
        return ShopPurchaseResult.REROLLED;
    }

    /**
     * A fresh, independent, uniform roll of every IV and EV -- not a nudge away from the current
     * value. That is the whole point of a gamble: the old roll has no bearing on the new one, in
     * either direction.
     */
    private static void applyReroll(Pokemon pokemon) {
        var random = ThreadLocalRandom.current();

        IVs ivs = pokemon.getIvs();
        for (String key : STAT_KEYS) {
            ivs.set(PokemonStatNames.statFor(key), RaidBossTraits.rollIv(random));
        }

        EVs evs = pokemon.getEvs();
        Map<String, Integer> rolledEvs = RaidBossTraits.rollEvs(STAT_KEYS, random);
        rolledEvs.forEach((key, value) -> evs.set(PokemonStatNames.statFor(key), value));
    }

    private static Pokemon deserialize(ServerPlayer player, BossSnapshot snapshot) {
        try {
            return Pokemon.Companion.loadFromNBT(player.registryAccess(), snapshot.pokemonNbt());
        } catch (RuntimeException ex) {
            RaidLog.error("Personal boss snapshot for " + snapshot.species() + " ("
                    + player.getGameProfile().getName() + ") failed to deserialize", ex);
            return null;
        }
    }

}
