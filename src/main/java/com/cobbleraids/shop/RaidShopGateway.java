package com.cobbleraids.shop;

import com.cobbleraids.catching.BossSnapshot;
import com.cobbleraids.catching.DefeatedBossSnapshots;
import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.network.ShopActionPayload;
import com.cobbleraids.network.ShopEntryPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.reward.points.RaidPointsStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the shop screen is allowed to ask for, and what it is told back.
 *
 * <p>The screen is a view. It sends "show me page three" and "buy this id", and everything else --
 * what page three contains, what that id costs, whether the player already owns it, what their
 * balance is afterwards -- is answered here from server state. Nothing the client sends is used as
 * anything but a request.
 *
 * <p>A page is re-sent after every purchase rather than letting the screen adjust its own copy. A
 * client that decremented its own balance would show a purchase that had not happened, and the one
 * thing a shop must never do is disagree with itself about whether you were charged.
 *
 * <p>One page is not from {@link ShopCatalogManager} at all: if the player has any
 * {@link DefeatedBossSnapshots}, a synthetic "Your Bosses" page is appended after the catalogue's
 * own pages, built fresh per request the same way the catalogue's own per-player {@code remaining}
 * counts already are -- this class already folds per-player state into an otherwise-global page,
 * so the personal page is that same idea, not a new one.
 */
public final class RaidShopGateway {

    private RaidShopGateway() {}

    /** Opens the shop at its first page. */
    public static void open(ServerPlayer player) {
        sendPage(player, 0);
    }

    /** A personal entry's id, e.g. "personal:cobblemon:garchomp" or "reroll:cobblemon:garchomp". */
    private static final String PERSONAL_PREFIX = "personal:";
    private static final String REROLL_PREFIX = "reroll:";

    /** Handles a page turn or a purchase from the screen. */
    public static void handle(ServerPlayer player, ShopActionPayload action) {
        if (!action.isPurchase()) {
            sendPage(player, action.pageIndex());
            return;
        }
        String id = action.entryId();
        ShopPurchaseResult result;
        String message;
        // Checked before the catalogue lookup, not after: a coincidentally-prefixed operator id in
        // the global catalogue must never be shadowed by this, and checking first makes that
        // structurally impossible rather than merely unlikely.
        if (id.startsWith(REROLL_PREFIX)) {
            ResourceLocation species = ResourceLocation.tryParse(id.substring(REROLL_PREFIX.length()));
            result = species == null ? ShopPurchaseResult.UNKNOWN_ENTRY
                    : PersonalBossShopService.reroll(player, species);
            message = result.message();
        } else if (id.startsWith(PERSONAL_PREFIX)) {
            ResourceLocation species = ResourceLocation.tryParse(id.substring(PERSONAL_PREFIX.length()));
            result = species == null ? ShopPurchaseResult.UNKNOWN_ENTRY
                    : PersonalBossShopService.purchase(player, species);
            message = result.message();
        } else {
            ShopEntry attempted = ShopCatalogManager.index().get(id);
            result = ShopPurchaseService.purchase(player, id);
            message = result == ShopPurchaseResult.LIMIT_REACHED
                    ? ShopPurchaseRules.limitMessage(attempted)
                    : result.message();
        }
        player.sendSystemMessage(Component.literal(message)
                .withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED));
        // Re-sent whether it worked or not: a refusal still has to leave the screen showing the
        // truth, and a limit being reached (or a slot being spent, or a reroll changing its own
        // numbers) is exactly what changes how a cell should look.
        sendPage(player, action.pageIndex());
    }

    private static void sendPage(ServerPlayer player, int requestedPage) {
        List<ShopPageView> catalogPages = ShopCatalogManager.pages();
        Map<ResourceLocation, BossSnapshot> personal = DefeatedBossSnapshots.forPlayer(player.getUUID());
        boolean hasPersonalPage = !personal.isEmpty();
        int pageCount = catalogPages.size() + (hasPersonalPage ? 1 : 0);
        if (pageCount == 0) {
            ServerPlayNetworking.send(player,
                    new ShopPagePayload("Raid Shop", 0, 1, RaidPointsStore.balance(player.getUUID()), List.of()));
            return;
        }
        // Clamped rather than rejected: the catalogue (or the personal page's presence) can shrink
        // under a player who has the screen open, and a page that no longer exists should land
        // somewhere sensible, not nowhere.
        int index = Math.max(0, Math.min(requestedPage, pageCount - 1));
        String heading;
        List<ShopEntryPayload> entries;
        if (index < catalogPages.size()) {
            heading = catalogPages.get(index).heading();
            entries = buildCatalogEntries(catalogPages.get(index), player);
        } else {
            // Always the last page, so page 0 -- and every existing bookmark or expectation about
            // it -- stays exactly what it already was.
            heading = "Your Bosses";
            entries = buildPersonalEntries(personal);
        }
        ServerPlayNetworking.send(player, new ShopPagePayload(heading, index, pageCount,
                RaidPointsStore.balance(player.getUUID()), entries));
    }

    private static List<ShopEntryPayload> buildCatalogEntries(ShopPageView page, ServerPlayer player) {
        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());
        Instant now = Instant.now();
        List<ShopEntryPayload> entries = new ArrayList<>(page.entries().size());
        for (ShopEntry entry : page.entries()) {
            // Sent as a count rather than a boolean so the cell can say "3 left" instead of only
            // "gone". A limit the player cannot see coming is a limit that reads as a bug.
            int remaining = ShopPurchaseRules.remaining(entry, record.purchasesOf(entry.id()), now);
            int limit = entry.isLimited() ? entry.limit() : 0;
            if (entry.isPokemon()) {
                entries.add(ShopEntryPayload.pokemon(entry.id(), entry.cost(),
                        entry.pokemon().species(), entry.pokemon().level(),
                        entry.pokemon().shiny(), remaining, limit));
            } else {
                ResourceLocation itemId = ResourceLocation.tryParse(entry.item().itemId());
                if (itemId == null) continue;
                entries.add(ShopEntryPayload.item(entry.id(), entry.cost(), itemId,
                        entry.item().count(), remaining, limit));
            }
        }
        return entries;
    }

    /**
     * One cell per saved snapshot, priced per its own rarity tier. Sorted by species id rather than
     * left in map order: {@link DefeatedBossSnapshots} stores them in a {@code ConcurrentHashMap},
     * which has no stable iteration order at all, and a personal page whose cells reshuffle between
     * one open and the next reads as broken even though nothing is actually wrong.
     */
    private static List<ShopEntryPayload> buildPersonalEntries(Map<ResourceLocation, BossSnapshot> personal) {
        CobbleRaidsConfig.PersonalBossShop config = CobbleRaidsConfigManager.get().personalBossShop();
        List<BossSnapshot> sorted = new ArrayList<>(personal.values());
        sorted.sort(Comparator.comparing(snapshot -> snapshot.species().toString()));

        List<ShopEntryPayload> entries = new ArrayList<>(sorted.size());
        for (BossSnapshot snapshot : sorted) {
            entries.add(ShopEntryPayload.personalPokemon(
                    "personal:" + snapshot.species(), config.buyCostFor(snapshot.rarityTier()),
                    // Bare path, not the full "namespace:path" -- ShopSpeciesIcons/ShopPokemonPortraits
                    // key their lookups on the same bare species name a catalogue entry's own
                    // pokemon().species() already provides, and this needs to match it exactly to
                    // resolve the same icons.
                    snapshot.species().getPath(), snapshot.level(), snapshot.shiny(),
                    snapshot.ivPercent(), snapshot.evPercent(), true, config.rerollCost()));
        }
        return entries;
    }
}
