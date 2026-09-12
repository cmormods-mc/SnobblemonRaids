package com.cobbleraids.command;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.reward.points.RaidPointsStore;
import com.cobbleraids.shop.ShopCatalog;
import com.cobbleraids.shop.RaidShopGateway;
import com.cobbleraids.shop.ShopCatalogManager;
import com.cobbleraids.shop.ShopEntry;
import com.cobbleraids.shop.ShopPageView;
import com.cobbleraids.shop.ShopPurchaseResult;
import com.cobbleraids.shop.ShopPurchaseRules;
import com.cobbleraids.shop.ShopPurchaseService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reading and using the raid shop from chat.
 *
 * <p>Here before the screen is, and staying after it. Everything the GUI will do goes through
 * {@link ShopPurchaseService}, so this exercises the real purchase path rather than a test double
 * -- which means the economy can be tuned, and every refusal reason seen, before a single pixel is
 * drawn. It is also the fallback for a player whose client cannot open the screen.
 */
public final class RaidShopCommand {

    private RaidShopCommand() {}

    private static final SuggestionProvider<CommandSourceStack> ENTRY_IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(ShopCatalogManager.index().keySet(), builder);

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("shop")
                                .executes(context -> list(context.getSource()))
                                .then(Commands.literal("buy")
                                        .then(Commands.argument("entry", StringArgumentType.word())
                                                .suggests(ENTRY_IDS)
                                                .executes(context -> buy(context.getSource(),
                                                        StringArgumentType.getString(context, "entry")))))
                                .then(Commands.literal("open")
                                        .executes(context -> open(context.getSource())))
                                .then(Commands.literal("reload")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> reload(context.getSource()))))
        ));
    }

    private static int list(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ShopCatalog catalog = ShopCatalogManager.get();
        int balance = RaidPointsStore.balance(player.getUUID());
        java.time.Instant now = java.time.Instant.now();
        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());

        source.sendSuccess(() -> CommandFormat.header("Raid Shop")
                .append(Component.literal("  " + balance + " RP").withStyle(ChatFormatting.AQUA)), false);
        if (catalog.totalEntries() == 0) {
            source.sendSuccess(() -> CommandFormat.row("Nothing is for sale yet."), false);
            return 0;
        }
        for (ShopPageView page : ShopCatalogManager.pages()) {
            source.sendSuccess(() -> CommandFormat.row(page.heading())
                    .withStyle(ChatFormatting.GOLD), false);
            for (ShopEntry entry : page.entries()) {
                // Coloured by what the player can actually do with it right now, so a long list
                // reads as a shortlist.
                ChatFormatting colour = balance >= entry.cost() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY;
                String base = entry.isPokemon()
                        ? entry.pokemon().displayName() + " Lv." + entry.pokemon().level()
                        : entry.item().count() + "x " + entry.item().itemId();
                String label = entry.isLimited()
                        ? base + "  (" + ShopPurchaseRules.remaining(entry,
                                record.purchasesOf(entry.id()), now)
                                + "/" + entry.limit() + " left)"
                        : base;
                source.sendSuccess(() -> CommandFormat.row("  " + CommandFormat.pad(entry.id(), 20)
                        + CommandFormat.pad(entry.cost() + " RP", 10) + label).withStyle(colour), false);
            }
        }
        return catalog.totalEntries();
    }

    private static int buy(CommandSourceStack source, String entryId) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ShopEntry entry = ShopCatalogManager.index().get(entryId);

        ShopPurchaseResult result = ShopPurchaseService.purchase(player, entryId);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()).withStyle(ChatFormatting.GREEN), false);
            source.sendSuccess(() -> Component.literal("Balance: "
                    + RaidPointsStore.balance(player.getUUID()) + " RP").withStyle(ChatFormatting.AQUA), false);
            return 1;
        }
        if (result == ShopPurchaseResult.LIMIT_REACHED) {
            source.sendFailure(Component.literal(ShopPurchaseRules.limitMessage(entry)));
            return 0;
        }
        if (result == ShopPurchaseResult.NOT_ENOUGH_POINTS) {
            int shortfall = ShopPurchaseRules.shortfall(entry, RaidPointsStore.balance(player.getUUID()));
            source.sendFailure(Component.literal(result.message() + " You need " + shortfall + " more."));
            return 0;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        RaidShopGateway.open(source.getPlayerOrException());
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        ShopCatalog catalog = ShopCatalogManager.reload();
        source.sendSuccess(() -> Component.literal("Reloaded the shop: " + catalog.totalEntries()
                + " entries across " + catalog.sections().size() + " sections.")
                .withStyle(ChatFormatting.GREEN), true);
        return catalog.totalEntries();
    }
}
