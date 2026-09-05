package com.cobbleraids.command;

import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class RaidAdminBossOps {
    private static final double NEAREST_RADIUS = 64.0;
    private RaidAdminBossOps() {}

    static int despawnNearest(CommandSourceStack source) {
        PokemonEntity nearest = null;
        double best = NEAREST_RADIUS * NEAREST_RADIUS;
        Vec3 origin = source.getPosition();
        for (var entity : source.getLevel().getAllEntities()) {
            if (!(entity instanceof PokemonEntity pokemon) || pokemon.isRemoved() || !RaidBossEntityMarker.isRaidBoss(pokemon)) continue;
            double distance = pokemon.distanceToSqr(origin);
            if (distance <= best) { best = distance; nearest = pokemon; }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No CobbleRaids boss found within " + (int) NEAREST_RADIUS + " blocks."));
            return 0;
        }
        String id = RaidBossEntityMarker.definitionId(nearest).map(ResourceLocation::toString).orElse("unknown");
        safelyRemove(nearest);
        source.sendSuccess(() -> Component.literal("Despawned nearest raid boss: " + id)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    static int despawnAll(CommandSourceStack source) {
        List<PokemonEntity> bosses = allBosses(source.getServer());
        bosses.forEach(RaidAdminBossOps::safelyRemove);
        source.sendSuccess(() -> Component.literal("Despawned " + bosses.size() + " CobbleRaids boss(es).")
                .withStyle(ChatFormatting.GREEN), true);
        return bosses.size();
    }

    static void safelyRemove(PokemonEntity boss) {
        RaidSession session = sessionForBoss(boss);
        if (session != null) { RaidLifecycleCoordinator.abort(session); return; }
        RaidLobbyManager.cancelForBoss(boss);
        if (!boss.isRemoved()) boss.discard();
        // Drop it from the scheduler's tracking now rather than leaving a stale entry that would
        // otherwise sit there -- still counted against max_active_raids/max_concurrent and still
        // blocking nearby spawns via min_distance_between_raids -- until its full despawn timer runs out.
        RaidSpawnScheduler.forget(boss.getUUID());
    }

    static RaidSession sessionForBoss(PokemonEntity boss) {
        if (boss == null) return null;
        UUID bossId = boss.getUUID();
        for (RaidSession session : RaidRegistry.all()) {
            if (session.getBossEntity() != null && bossId.equals(session.getBossEntity().getUUID())) return session;
        }
        return null;
    }

    static List<PokemonEntity> allBosses(MinecraftServer server) {
        List<PokemonEntity> bosses = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            if (entity instanceof PokemonEntity pokemon && !pokemon.isRemoved() && RaidBossEntityMarker.isRaidBoss(pokemon)) {
                bosses.add(pokemon);
            }
        }
        return bosses;
    }
}
