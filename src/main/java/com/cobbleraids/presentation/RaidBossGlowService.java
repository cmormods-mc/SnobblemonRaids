package com.cobbleraids.presentation;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossLookup;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Gives a raid boss the vanilla "Glowing" outline (visible through terrain) whenever a player is
 * within boss_glow.radius_blocks, tinted per rarity tier via the same ChatFormatting
 * RaidTierPresentation already uses for that tier's name and particle. Covers every boss --
 * natural, admin-spawned, or testwild -- since all of them are created through the single
 * RaidBossSpawner.spawnAt() choke point that registers here.
 *
 * <p>A thin static façade over {@link TrackedBossService}, which owns the register/recover/prune/
 * tick plumbing this service used to duplicate with {@link RenownBoonSyncService}. Nothing here
 * needs a per-boss payload beyond "is tracked", so the tracker below uses {@link Boolean#TRUE} as
 * a constant marker.
 */
public final class RaidBossGlowService {
    private static final String TEAM_PREFIX = "cobbleraids_glow_";
    private static final int GLOW_DURATION_TICKS = 30;
    private static final Tracker TRACKER = new Tracker();

    private RaidBossGlowService() {}

    /**
     * Called once, right after a boss is created, regardless of how it was spawned -- and again by
     * {@link TrackedBossService#onEntityLoaded} for a boss that survived a restart without ever
     * going through spawn.
     */
    public static void register(PokemonEntity boss, ServerLevel level) {
        TRACKER.register(boss, level, Boolean.TRUE);
    }

    public static void onServerStopping(MinecraftServer server) {
        TRACKER.onServerStopping(server);
    }

    /** Every boss this service is tracking, and the dimension it was registered in. For auditing. */
    public static Map<UUID, ResourceLocation> trackedBosses() {
        return TRACKER.trackedBosses();
    }

    /** Team name for a tier, so an audit can spot members that are no longer tracked bosses. */
    public static String teamName(RaidRarityTier tier) {
        return TEAM_PREFIX + tier.serializedName();
    }

    public static void tick(MinecraftServer server) {
        TRACKER.tick(server);
    }

    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        TRACKER.onEntityLoaded(entity, level);
    }

    public static void onEntityUnloaded(Entity entity, ServerLevel level) {
        TRACKER.onEntityUnloaded(entity, level);
    }

    private static void applyGlow(MinecraftServer server, PokemonEntity boss, RaidRarityTier tier) {
        PlayerTeam team = teamFor(server.getScoreboard(), tier);
        if (!team.getPlayers().contains(boss.getScoreboardName())) {
            server.getScoreboard().addPlayerToTeam(boss.getScoreboardName(), team);
        }
        boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false));
    }

    /**
     * Resolved from the live scoreboard on every use rather than cached in a static map. A PlayerTeam
     * holds a final reference to its Scoreboard, and ServerScoreboard holds one to its MinecraftServer,
     * so a static cache here pinned an entire dead server (player list, every ServerLevel, their loaded
     * entities) for the life of the JVM once a world was closed -- and handed back a team belonging to
     * that dead scoreboard on the next world, where it is not registered, silently dropping the tier
     * tint. The lookup it replaces is a plain map get on an at-most-once-per-second path.
     */
    private static PlayerTeam teamFor(Scoreboard scoreboard, RaidRarityTier tier) {
        String name = TEAM_PREFIX + tier.serializedName();
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) team = scoreboard.addPlayerTeam(name);
        ChatFormatting color = RaidTierPresentation.color(tier);
        // Only written when it actually differs; setColor broadcasts a team-update packet to everyone.
        if (team.getColor() != color) team.setColor(color);
        return team;
    }

    private static final class Tracker extends TrackedBossService<Boolean> {
        Tracker() {
            super("glow service");
        }

        @Override
        protected Optional<Boolean> recoverPayload(PokemonEntity pokemon) {
            return RaidBossEntityMarker.isRaidBoss(pokemon) ? Optional.of(Boolean.TRUE) : Optional.empty();
        }

        @Override
        protected void refresh(MinecraftServer server, PokemonEntity boss, Boolean payload) {
            CobbleRaidsConfig.BossGlow config = CobbleRaidsConfigManager.get().bossGlow();
            // Deliberately not gating tracking/pruning on this, only the glow itself: register() runs
            // for every boss whatever the config says, so bailing out earlier than this would leave
            // the tracked map growing by one UUID per boss for the life of the server.
            if (!config.enabled()) return;

            RaidRarityTier tier = RaidBossEntityMarker.definitionId(boss)
                    .map(RaidDefinitionRegistry::get)
                    .map(RaidDefinition::rarityTier)
                    .orElse(null);
            if (tier == null) return;

            if (RaidBossLookup.hasNearbyPlayer(server, boss, config.radiusBlocks())) {
                applyGlow(server, boss, tier);
            } else if (boss.hasEffect(MobEffects.GLOWING)) {
                boss.removeEffect(MobEffects.GLOWING);
            }
        }

        /**
         * Takes a boss back out of its glow team and clears the effect.
         *
         * <p>The scoreboard half matters more than it looks. addPlayerToTeam stores the member in the
         * Scoreboard's own map, which is saved into scoreboard.dat and replayed to every client that
         * joins -- and an entity's scoreboard name is its UUID, so leaving them behind meant one
         * permanent, useless entry in the world save per raid boss that ever glowed. On a server that
         * has been up for months that is the mod quietly growing the world.
         */
        @Override
        protected void onUntracked(MinecraftServer server, UUID bossId, PokemonEntity boss, Boolean payload) {
            Scoreboard scoreboard = server.getScoreboard();
            String member = boss != null ? boss.getScoreboardName() : bossId.toString();
            // The single-argument form resolves the team itself and is a no-op when the member is on
            // none; the two-argument form throws if it guesses wrong.
            scoreboard.removePlayerFromTeam(member);
            if (boss != null && boss.hasEffect(MobEffects.GLOWING)) boss.removeEffect(MobEffects.GLOWING);
        }
    }
}
