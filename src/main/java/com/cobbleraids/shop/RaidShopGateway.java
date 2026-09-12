package com.cobbleraids.shop;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.network.ShopActionPayload;
import com.cobbleraids.network.ShopEntryPayload;
import com.cobbleraids.network.ShopPagePayload;
import com.cobbleraids.reward.points.RaidPointsStore;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class RaidShopGateway {

    private RaidShopGateway() {}

    /** Opens the shop at its first page. */
    public static void open(ServerPlayer player) {
        sendPage(player, 0);
    }

    /** Handles a page turn or a purchase from the screen. */
    public static void handle(ServerPlayer player, ShopActionPayload action) {
        if (!action.isPurchase()) {
            sendPage(player, action.pageIndex());
            return;
        }
        ShopPurchaseResult result = ShopPurchaseService.purchase(player, action.entryId());
        player.sendSystemMessage(Component.literal(result.message())
                .withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED));
        // Re-sent whether it worked or not: a refusal still has to leave the screen showing the
        // truth, and ALREADY_OWNED in particular changes what the cell should look like.
        sendPage(player, action.pageIndex());
    }

    private static void sendPage(ServerPlayer player, int requestedPage) {
        List<ShopPageView> pages = ShopCatalogManager.get().pages();
        if (pages.isEmpty()) {
            ServerPlayNetworking.send(player,
                    new ShopPagePayload("Raid Shop", 0, 1, RaidPointsStore.balance(player.getUUID()), List.of()));
            return;
        }
        // Clamped rather than rejected: the catalogue can shrink under a player who has the screen
        // open, and a page that no longer exists should land somewhere sensible, not nowhere.
        int index = Math.max(0, Math.min(requestedPage, pages.size() - 1));
        ShopPageView page = pages.get(index);
        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());

        List<ShopEntryPayload> entries = new ArrayList<>(page.entries().size());
        for (ShopEntry entry : page.entries()) {
            boolean owned = entry.oncePerPlayer() && record.hasPurchased(entry.id());
            if (entry.isPokemon()) {
                entries.add(ShopEntryPayload.pokemon(entry.id(), entry.cost(),
                        entry.pokemon().species(), entry.pokemon().level(),
                        entry.pokemon().shiny(), owned));
            } else {
                ResourceLocation itemId = ResourceLocation.tryParse(entry.item().itemId());
                if (itemId == null) continue;
                entries.add(ShopEntryPayload.item(entry.id(), entry.cost(), itemId, entry.item().count())
                        .withOwned(owned));
            }
        }
        ServerPlayNetworking.send(player, new ShopPagePayload(page.heading(), index, pages.size(),
                RaidPointsStore.balance(player.getUUID()), entries));
    }
}
