package com.cobbleraids.command;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.presentation.CommandFormat;
import com.cobbleraids.presentation.TitleDisplayService;
import com.cobbleraids.title.TitleCatalogManager;
import com.cobbleraids.title.TitleDefinition;
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
 * Listing and selecting trainer titles. See {@link com.cobbleraids.title.TitleService} for how a
 * title is earned, and {@link TitleDisplayService} for how the selected one is shown.
 */
public final class RaidTitleCommand {
    private RaidTitleCommand() {}

    /** Only what the player has actually unlocked -- the same idea as {@code RaidShopCommand}'s own
     * suggestion provider, so tab completion can never suggest a title the set below would refuse. */
    private static final SuggestionProvider<CommandSourceStack> UNLOCKED_TITLES = (context, builder) -> {
        try {
            RaidPlayerRecord record = RaidPlayerRecords.get(context.getSource().getPlayerOrException().getUUID());
            return SharedSuggestionProvider.suggest(record.unlockedTitles(), builder);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            return builder.buildFuture();
        }
    };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("title")
                                .executes(context -> list(context.getSource()))
                                .then(Commands.literal("list")
                                        .executes(context -> list(context.getSource())))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(UNLOCKED_TITLES)
                                                .executes(context -> set(context.getSource(),
                                                        StringArgumentType.getString(context, "id")))))
                                .then(Commands.literal("clear")
                                        .executes(context -> clear(context.getSource()))))
        ));
    }

    private static int list(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());

        source.sendSuccess(() -> CommandFormat.header("Trainer Titles"), false);
        if (record.unlockedTitles().isEmpty()) {
            source.sendSuccess(() -> CommandFormat.row("You have not unlocked any titles yet."), false);
            return 0;
        }
        for (String id : record.unlockedTitles()) {
            TitleDefinition title = TitleCatalogManager.index().get(id);
            if (title == null) continue; // an operator removed it from the catalogue since
            boolean selected = id.equals(record.selectedTitle());
            source.sendSuccess(() -> CommandFormat.row((selected ? "> " : "  ") + title.display())
                    .withStyle(title.chatColor()), false);
        }
        return record.unlockedTitles().size();
    }

    private static int set(CommandSourceStack source, String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!RaidPlayerRecords.selectTitle(source.getServer(), player.getUUID(), id)) {
            source.sendFailure(Component.literal("You have not unlocked that title.").withStyle(ChatFormatting.RED));
            return 0;
        }
        TitleDisplayService.apply(source.getServer(), player);
        TitleDefinition title = TitleCatalogManager.index().get(id);
        source.sendSuccess(() -> Component.literal("Wearing: " + (title != null ? title.display() : id))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaidPlayerRecords.selectTitle(source.getServer(), player.getUUID(), null);
        TitleDisplayService.apply(source.getServer(), player);
        source.sendSuccess(() -> Component.literal("Title cleared.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
