package com.cobbleraids.shop;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.pokemon.PokemonStatNames;
import com.cobbleraids.reward.points.RaidPointsStore;
import java.time.Instant;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Turning a click into a purchase.
 *
 * <p>The click carries an entry id and nothing else. Price, payload and stock rule are all re-read
 * here from the catalogue, so a client that sends a made-up price, a made-up slot or a stale page
 * buys exactly what the operator listed or nothing at all.
 *
 * <p><b>Order matters, and it is deliberate.</b> Everything that can fail happens before any points
 * are taken: the entry is resolved, the item or species is resolved, and a Pokemon purchase is
 * given somewhere to go. Points are spent last, once nothing is left that can go wrong. This runs
 * on the server thread inside a single packet or command handler, so the balance cannot change
 * between the check and the spend -- which is what lets the whole thing avoid a refund path, and a
 * refund path is precisely where a duplication bug would live.
 */
public final class ShopPurchaseService {

    private ShopPurchaseService() {}

    public static ShopPurchaseResult purchase(ServerPlayer player, String entryId) {
        ShopEntry entry = ShopCatalogManager.index().get(entryId);
        Instant now = Instant.now();
        ShopPurchaseResult blocked = ShopPurchaseRules.check(
                entry,
                RaidPointsStore.balance(player.getUUID()),
                entry == null ? null : RaidPlayerRecords.get(player.getUUID()).purchasesOf(entry.id()),
                now);
        if (blocked != null) return blocked;

        try {
            return entry.isPokemon() ? givePokemon(player, entry, now) : giveItem(player, entry, now);
        } catch (RuntimeException ex) {
            RaidLog.error("Shop purchase of " + entry.id() + " by "
                    + player.getGameProfile().getName() + " failed", ex);
            return ShopPurchaseResult.FAILED;
        }
    }

    private static ShopPurchaseResult giveItem(ServerPlayer player, ShopEntry entry, Instant now) {
        ResourceLocation itemId = ResourceLocation.tryParse(entry.item().itemId());
        if (itemId == null) return ShopPurchaseResult.UNRESOLVED;
        Item item = BuiltInRegistries.ITEM.get(itemId);
        // The registry answers AIR for anything it does not know, so the id has to be checked back
        // out of it. Without this a listing for a mod that is not installed silently sells air.
        if (!BuiltInRegistries.ITEM.getKey(item).equals(itemId)) {
            RaidLog.error("Shop entry " + entry.id() + " sells " + itemId
                    + ", which is not registered. Is the mod that owns it installed?");
            return ShopPurchaseResult.UNRESOLVED;
        }

        settle(player, entry, now);
        // placeItemBackInInventory drops what will not fit at the player's feet rather than
        // destroying it, so a full inventory is never a reason to refuse the sale.
        int remaining = entry.item().count();
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int amount = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(amount);
            player.getInventory().placeItemBackInInventory(stack);
            remaining -= amount;
        }
        return ShopPurchaseResult.BOUGHT;
    }

    private static ShopPurchaseResult givePokemon(ServerPlayer player, ShopEntry entry, Instant now) {
        Pokemon pokemon = build(entry.pokemon());
        if (pokemon == null) {
            RaidLog.error("Shop entry " + entry.id() + " could not build "
                    + entry.pokemon().species() + "; check the species id.");
            return ShopPurchaseResult.UNRESOLVED;
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        boolean partyHasRoom = party.getFirstAvailablePosition() != null;
        // Asked before anything is spent, which is the whole reason this is checked rather than
        // discovered from a failed add(): there is then no purchase to unwind.
        if (!partyHasRoom && Cobblemon.INSTANCE.getStorage().getPC(player).getFirstAvailablePosition() == null) {
            return ShopPurchaseResult.NO_ROOM;
        }

        settle(player, entry, now);
        if (partyHasRoom && party.add(pokemon)) {
            return ShopPurchaseResult.BOUGHT;
        }
        Cobblemon.INSTANCE.getStorage().getPC(player).add(pokemon);
        return ShopPurchaseResult.BOUGHT_TO_PC;
    }

    /** Takes the points and remembers the purchase. Called only once nothing can still fail. */
    private static void settle(ServerPlayer player, ShopEntry entry, Instant now) {
        RaidPointsStore.spend(player.getServer(), player.getUUID(), entry.cost());
        if (entry.isLimited()) {
            RaidPlayerRecords.recordPurchase(player.getServer(), player.getUUID(), entry.id(),
                    ShopPurchaseRules.windowOf(entry, now));
        }
        // One flush, after both writes rather than inside each, and for every purchase rather than
        // only the limited ones. Points and tallies share a SavedData, so a single write covers
        // both -- and an unlimited entry used to spend points with nothing forcing them to disk,
        // which meant a crash before the next autosave handed back the points and kept the item.
        RaidPlayerRecords.flush(player.getServer());
        RaidLog.info("Shop: " + player.getGameProfile().getName() + " bought " + entry.id()
                + " for " + entry.cost() + " RP");
    }

    /**
     * Builds the purchased Pokemon from its pinned fields.
     *
     * <p>Routed through PokemonProperties for the reason raid bosses are: the setters that matter
     * -- ivs, evs, ability, held item -- are Kotlin-internal and not API, and this is what
     * Cobblemon's own /pokegive uses.
     */
    private static Pokemon build(ShopPokemonGift gift) {
        PokemonProperties properties = new PokemonProperties();
        properties.setSpecies(gift.species());
        properties.setLevel(gift.level());
        if (gift.shiny()) properties.setShiny(Boolean.TRUE);
        if (gift.nature() != null) properties.setNature(gift.nature());
        if (gift.ability() != null) properties.setAbility(gift.ability());
        if (gift.form() != null) properties.setForm(gift.form());
        if (gift.teraType() != null) properties.setTeraType(gift.teraType());
        if (gift.heldItem() != null) properties.setHeldItem(gift.heldItem());

        // Only stats the operator actually pinned are set. An untouched stat keeps Cobblemon's own
        // roll, which is what makes an empty traits block a no-op rather than a zeroed Pokemon.
        if (!gift.ivs().isEmpty()) {
            IVs ivs = new IVs();
            gift.ivs().forEach((stat, value) -> ivs.set(PokemonStatNames.statFor(stat), value));
            properties.setIvs(ivs);
        }
        if (!gift.evs().isEmpty()) {
            EVs evs = new EVs();
            gift.evs().forEach((stat, value) -> evs.set(PokemonStatNames.statFor(stat), value));
            properties.setEvs(evs);
        }
        return properties.create();
    }
}
