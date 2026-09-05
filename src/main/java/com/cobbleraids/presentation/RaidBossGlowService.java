package com.cobbleraids.presentation;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Gives a raid boss the vanilla "Glowing" outline (visible through terrain) whenever a player is
 * within boss_glow.radius_blocks, tinted per rarity tier via the same ChatFormatting
 * RaidTierPresentation already uses for that tier's name and particle. Covers every boss --
 * natural, admin-spawned, or testwild -- since all of them are created through the single
 * RaidBossSpawner.spawnAt() choke point that registers here.
 */
public final class RaidBossGlowService {
    private static final String TEAM_PREFIX = "cobbleraids_glow_";
    private static final int GLOW_DURATION_TICKS = 30;
    private static final Map<UUID, ResourceLocation> TRACKED = new ConcurrentHashMap<>();
    private static final Map<RaidRarityTier, PlayerTeam> TEAMS = new EnumMap<>(RaidRarityTier.class);
    private static long tickCounter;

    private RaidBossGlowService() {}

    /** Called once, right after a boss is created, regardless of how it was spawned. */
    public static void register(PokemonEntity boss, ServerLevel level) {
        TRACKED.put(boss.getUUID(), level.dimension().location());
    }

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % 20L) != 0L) return;
        if (TRACKED.isEmpty()) return;

        CobbleRaidsConfig.BossGlow config = CobbleRaidsConfigManager.get().bossGlow();
        if (!config.enabled()) return;
        double radiusSqr = config.radiusBlocks() * config.radiusBlocks();

        Iterator<Map.Entry<UUID, ResourceLocation>> iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ResourceLocation> entry = iterator.next();
            PokemonEntity boss = resolveBoss(server, entry.getKey(), entry.getValue());
            if (boss == null || boss.isRemoved() || !RaidBossEntityMarker.isRaidBoss(boss)) {
                iterator.remove();
                continue;
            }

            RaidRarityTier tier = RaidBossEntityMarker.definitionId(boss)
                    .map(RaidDefinitionRegistry::get)
                    .map(RaidDefinition::rarityTier)
                    .orElse(null);
            if (tier == null) continue;

            if (hasNearbyPlayer(server, boss, radiusSqr)) {
                applyGlow(server, boss, tier);
            } else if (boss.hasEffect(MobEffects.GLOWING)) {
                boss.removeEffect(MobEffects.GLOWING);
            }
        }
    }

    private static void applyGlow(MinecraftServer server, PokemonEntity boss, RaidRarityTier tier) {
        PlayerTeam team = teamFor(server.getScoreboard(), tier);
        if (!team.getPlayers().contains(boss.getScoreboardName())) {
            server.getScoreboard().addPlayerToTeam(boss.getScoreboardName(), team);
        }
        boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false));
    }

    private static PlayerTeam teamFor(Scoreboard scoreboard, RaidRarityTier tier) {
        return TEAMS.computeIfAbsent(tier, t -> {
            String name = TEAM_PREFIX + t.serializedName();
            PlayerTeam existing = scoreboard.getPlayerTeam(name);
            PlayerTeam team = existing != null ? existing : scoreboard.addPlayerTeam(name);
            team.setColor(RaidTierPresentation.color(t));
            return team;
        });
    }

    private static PokemonEntity resolveBoss(MinecraftServer server, UUID bossId, ResourceLocation dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return null;
        return level.getEntity(bossId) instanceof PokemonEntity pokemon ? pokemon : null;
    }

    private static boolean hasNearbyPlayer(MinecraftServer server, PokemonEntity boss, double radiusSqr) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == boss.level() && player.isAlive() && !player.isSpectator()
                    && player.distanceToSqr(boss) <= radiusSqr) return true;
        }
        return false;
    }
}
