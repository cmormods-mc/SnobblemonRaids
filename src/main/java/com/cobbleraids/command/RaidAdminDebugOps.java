package com.cobbleraids.command;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lobby.RaidLobby;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnHistory;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class RaidAdminDebugOps {
    private RaidAdminDebugOps() {}

    static int status(CommandSourceStack source) {
        List<PokemonEntity> bosses = RaidAdminBossOps.allBosses(source.getServer());
        int definitions = RaidDefinitionRegistry.all().size();
        int lobbies = RaidLobbyManager.all().size();
        int battles = RaidRegistry.all().size();
        int natural = RaidSpawnScheduler.activeCount(source.getServer());
        String rewardGui = RewardGuiBackends.active().name();
        source.sendSuccess(() -> Component.literal("CobbleRaids status: definitions=" + definitions
                + ", bosses=" + bosses.size() + ", lobbies=" + lobbies + ", battles=" + battles
                + ", naturalTracked=" + natural + ", rewardGui=" + rewardGui).withStyle(ChatFormatting.AQUA), false);
        return bosses.size() + battles + lobbies;
    }

    static int raids(CommandSourceStack source) {
        List<PokemonEntity> bosses = RaidAdminBossOps.allBosses(source.getServer());
        if (bosses.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No live CobbleRaids bosses are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        bosses.sort(Comparator.comparing(b -> RaidBossEntityMarker.definitionId(b)
                .map(ResourceLocation::toString).orElse("~unknown")));
        source.sendSuccess(() -> Component.literal("Live CobbleRaids bosses (" + bosses.size() + "):")
                .withStyle(ChatFormatting.GOLD), false);
        for (PokemonEntity boss : bosses) sendBoss(source, boss);
        return bosses.size();
    }

    private static void sendBoss(CommandSourceStack source, PokemonEntity boss) {
        String definition = RaidBossEntityMarker.definitionId(boss).map(ResourceLocation::toString).orElse("unknown");
        RaidSession session = RaidAdminBossOps.sessionForBoss(boss);
        RaidLobby lobby = RaidLobbyManager.get(boss);
        String state = session != null ? "battle:" + session.getStatus()
                : lobby != null ? "lobby:" + lobby.status() : "idle";
        source.sendSuccess(() -> Component.literal(" - " + definition + " | " + state + " | "
                + boss.level().dimension().location() + " @ " + pos(boss)
                + " | natural=" + RaidBossEntityMarker.isNatural(boss)), false);
        if (lobby != null) {
            long remaining = Math.max(0L, lobby.closesAtTick() - boss.level().getGameTime());
            source.sendSuccess(() -> Component.literal("    lobby players=" + lobby.joinedCount() + "/"
                    + lobby.definition().recruitment().maxPlayers() + ", remaining="
                    + String.format(Locale.ROOT, "%.1fs", remaining / 20.0)), false);
        }
        if (session != null) sendSession(source, session);
    }

    private static void sendSession(CommandSourceStack source, RaidSession session) {
        String timer = session.isTimed() ? String.format(Locale.ROOT, "%.1fs", session.getRemainingCombatTicks() / 20.0) : "unlimited";
        source.sendSuccess(() -> Component.literal("    hp=" + String.format(Locale.ROOT, "%.1f/%.1f", session.getCurrentHealth(), session.getMaxHealth())
                + ", activePlayers=" + session.getActiveParticipants().size() + "/" + session.getParticipants().size()
                + ", timer=" + timer + ", flee=" + session.isFleeAllowed()), false);
        Map<UUID, Double> shares = ContributionMath.percentages(session.getContributionSnapshot(), session.getActiveParticipants());
        for (Map.Entry<UUID, Double> entry : shares.entrySet()) {
            ServerPlayer player = source.getServer().getPlayerList().getPlayer(entry.getKey());
            String name = player == null ? entry.getKey().toString() : player.getGameProfile().getName();
            source.sendSuccess(() -> Component.literal("      " + name + ": "
                    + String.format(Locale.ROOT, "%.1f%%", entry.getValue())), false);
        }
    }

    private static String pos(PokemonEntity boss) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", boss.getX(), boss.getY(), boss.getZ());
    }

    static int history(CommandSourceStack source) {
        List<RaidSpawnHistory.Entry> entries = RaidSpawnHistory.recent();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No recorded natural-spawn attempts yet.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Recent natural-spawn attempts (" + entries.size() + ", oldest first):")
                .withStyle(ChatFormatting.GOLD), false);
        for (RaidSpawnHistory.Entry entry : entries) {
            ChatFormatting color = entry.outcome() == RaidSpawnHistory.Outcome.SUCCESS
                    ? ChatFormatting.GREEN : ChatFormatting.RED;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " - [%.1fs] %s @ %s -> %s: %s",
                    entry.tick() / 20.0, entry.player(), entry.dimension(), entry.outcome(), entry.detail()))
                    .withStyle(color), false);
        }
        return entries.size();
    }

    static int config(CommandSourceStack source) {
        CobbleRaidsConfig config = CobbleRaidsConfigManager.get();
        CobbleRaidsConfig.NaturalSpawning ns = config.naturalSpawning();
        source.sendSuccess(() -> Component.literal("CobbleRaids config (" + CobbleRaidsConfigManager.path() + "):")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(" natural_spawning: enabled=" + ns.enabled()
                + " checkIntervalTicks=" + ns.checkIntervalTicks()
                + " spawnAttemptChance=" + ns.spawnAttemptChance()
                + " attemptsPerCheck=" + ns.attemptsPerCheck()), false);
        source.sendSuccess(() -> Component.literal("   maxActiveRaids=" + ns.maxActiveRaids()
                + " maxActiveRaidsPerDimension=" + ns.maxActiveRaidsPerDimension()
                + " minDistanceBetweenRaids=" + ns.minDistanceBetweenRaids()), false);
        source.sendSuccess(() -> Component.literal("   playerDistance=" + ns.minDistanceFromPlayer() + ".."
                + ns.maxDistanceFromPlayer()
                + " locationAttempts=" + ns.locationAttempts()
                + " despawnPlayerRadius=" + ns.despawnPlayerRadius()), false);
        source.sendSuccess(() -> Component.literal("   defaultDespawnSeconds=" + ns.defaultDespawnSeconds()
                + " defaultDefinitionCooldownSeconds=" + ns.defaultDefinitionCooldownSeconds()
                + " announcementPrecision=" + ns.announcementPrecision().serializedName()), false);
        source.sendSuccess(() -> Component.literal(" recruitment_defaults: duration="
                + config.recruitmentDefaults().durationSeconds() + "s radius=" + config.recruitmentDefaults().radius()
                + " maxPlayers=" + config.recruitmentDefaults().maxPlayers()), false);
        source.sendSuccess(() -> Component.literal(" combat_defaults: timeLimit="
                + config.combatDefaults().timeLimitSeconds() + "s allowFlee=" + config.combatDefaults().allowFlee()), false);
        source.sendSuccess(() -> Component.literal(" tier_scaling: enabled=" + config.tierScaling().enabled()), false);
        source.sendSuccess(() -> Component.literal(" boss_glow: enabled=" + config.bossGlow().enabled()
                + " radiusBlocks=" + config.bossGlow().radiusBlocks()), false);
        source.sendSuccess(() -> Component.literal(" debug_logging=" + config.debugLogging()), false);
        return 1;
    }
}
