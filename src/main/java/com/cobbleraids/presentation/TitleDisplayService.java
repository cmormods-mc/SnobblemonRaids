package com.cobbleraids.presentation;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.title.TitleCatalogManager;
import com.cobbleraids.title.TitleDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Shows a player's selected trainer title as a vanilla scoreboard team prefix -- above their head and
 * in the tab list, with no client mixin at all.
 *
 * <p>Deliberately not cached the way {@link RaidBossGlowService} explains its own lookup is not:
 * team membership is per-join (there is no per-tick refresh to amortise the cost over), and a cached
 * {@code PlayerTeam} would pin a closed world's {@code Scoreboard} in memory on an integrated client
 * exactly as that class describes.
 */
public final class TitleDisplayService {
    private static final String TEAM_PREFIX = "cobbleraids_title_";

    private TitleDisplayService() {}

    /** Re-applies whatever title (if any) this player has selected. Call on join and on selection. */
    public static void apply(MinecraftServer server, ServerPlayer player) {
        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());
        Scoreboard scoreboard = server.getScoreboard();
        String selected = record.selectedTitle();
        TitleDefinition title = selected == null ? null : TitleCatalogManager.index().get(selected);
        if (title == null) {
            // No selection, or the operator removed a title a player had selected -- either way
            // there is nothing to show, and any previous title team membership must go with it.
            scoreboard.removePlayerFromTeam(player.getScoreboardName());
            return;
        }
        PlayerTeam team = teamFor(scoreboard, title);
        if (!team.getPlayers().contains(player.getScoreboardName())) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        }
    }

    private static PlayerTeam teamFor(Scoreboard scoreboard, TitleDefinition title) {
        String name = TEAM_PREFIX + title.id();
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) team = scoreboard.addPlayerTeam(name);
        // Only written when it actually differs; setPlayerPrefix/setColor each broadcast a
        // team-update packet to everyone, the same guard RaidBossGlowService.teamFor uses.
        if (team.getColor() != title.chatColor()) team.setColor(title.chatColor());
        Component prefix = Component.literal("[" + title.display() + "] ").withStyle(title.chatColor());
        if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
        return team;
    }
}
