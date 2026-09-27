package com.cobbleraids.title;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/**
 * Checks a player's raid history against the title catalogue and unlocks whatever newly qualifies.
 *
 * <p>Called after anything that moves a stat a title can be gated on -- a raid win or a boss catch --
 * the same way {@link com.cobbleraids.reward.points.RaidPointsStore} is read right after the record
 * that backs it changes. A title earned mid-session shows up immediately rather than on next login,
 * since the check is cheap: a handful of integer comparisons against a catalogue with, realistically,
 * a few dozen entries at most.
 */
public final class TitleService {
    private TitleService() {}

    public static void checkUnlocks(MinecraftServer server, UUID playerId) {
        RaidPlayerRecord record = RaidPlayerRecords.get(playerId);
        for (TitleDefinition title : TitleCatalogManager.get().titles()) {
            if (record.hasTitle(title.id())) continue;
            if (!qualifies(record, title)) continue;
            RaidPlayerRecords.unlockTitle(server, playerId, title.id());
            notify(server, playerId, title);
        }
    }

    // Package-private rather than private so TitleServiceTest can drive it directly against a plain
    // RaidPlayerRecord, with no MinecraftServer to bootstrap.
    static boolean qualifies(RaidPlayerRecord record, TitleDefinition title) {
        return switch (title.unlockType()) {
            case RAIDS_WON -> record.raidsWon() >= title.threshold();
            case TIER_WINS -> title.tier() != null && record.winsIn(title.tier()) >= title.threshold();
            case BOSSES_CAUGHT -> record.bossesCaught() >= title.threshold();
        };
    }

    private static void notify(MinecraftServer server, UUID playerId, TitleDefinition title) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return; // still unlocked; they will just find out next time they check
        player.sendSystemMessage(Component.literal("Title unlocked: " + title.display())
                .withStyle(title.chatColor())
                .append(Component.literal("  (/cobbleraids title set " + title.id() + ")")
                        .withStyle(ChatFormatting.GRAY)));
    }
}
