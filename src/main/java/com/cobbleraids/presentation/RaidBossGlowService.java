package com.cobbleraids.presentation;

import com.cobbleraids.RaidLog;
import com.cobbleraids.spawn.RaidBossLookup;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.entity.Entity;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
    private static long tickCounter;

    private RaidBossGlowService() {}

    /**
     * Called once, right after a boss is created, regardless of how it was spawned -- and again by
     * {@link #onEntityLoaded} for a boss that survived a restart without ever going through spawn.
     */
    public static void register(PokemonEntity boss, ServerLevel level) {
        TRACKED.put(boss.getUUID(), level.dimension().location());
    }

    /**
     * Drops all cross-server state on shutdown. Tracked ids are only meaningful for the server that
     * produced them, and an integrated (single-player) client reuses this JVM for every world it
     * opens, so anything left here would be read back against the next world's entities.
     */
    public static void onServerStopping(MinecraftServer server) {
        // Take every tracked boss out of its glow team first. Clearing the map alone would leave the
        // membership behind in the world save with nothing left that knows to remove it.
        for (Map.Entry<UUID, ResourceLocation> entry : Map.copyOf(TRACKED).entrySet()) {
            untrack(server, entry.getKey(), RaidBossLookup.resolve(server, entry.getKey(), entry.getValue()));
        }
        TRACKED.clear();
        tickCounter = 0L;
    }

    /** Every boss this service is tracking, and the dimension it was registered in. For auditing. */
    public static Map<UUID, ResourceLocation> trackedBosses() { return Map.copyOf(TRACKED); }

    /** Team name for a tier, so an audit can spot members that are no longer tracked bosses. */
    public static String teamName(RaidRarityTier tier) { return TEAM_PREFIX + tier.serializedName(); }

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % 20L) != 0L) return;
        if (TRACKED.isEmpty()) return;

        CobbleRaidsConfig.BossGlow config = CobbleRaidsConfigManager.get().bossGlow();
        // Deliberately NOT an early return when glow is switched off. register() is called for every
        // boss at spawn whatever the config says, so bailing out before the pruning loop below left
        // this map growing by one UUID per boss for the life of the server. Pruning is cheap and
        // bounded by the number of live bosses, so it runs either way and only the glow itself is
        // gated.
        boolean enabled = config.enabled();

        Iterator<Map.Entry<UUID, ResourceLocation>> iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ResourceLocation> entry = iterator.next();
            PokemonEntity boss = RaidBossLookup.resolve(server, entry.getKey(), entry.getValue());
            // "Does not resolve" means the chunk holding it is not loaded, which is not the same as
            // gone -- the Phase 32 distinction, which this service was still getting wrong. Dropping
            // an unloaded boss here was permanent, because register() only ever runs at spawn: the
            // boss came back when its chunk reloaded and never glowed again. A boss that is really
            // destroyed is untracked by onEntityUnloaded instead.
            if (shouldUntrack(boss != null, boss != null && boss.isRemoved(),
                    boss != null && RaidBossEntityMarker.isRaidBoss(boss))) {
                untrack(server, entry.getKey(), boss);
                iterator.remove();
                continue;
            }
            if (boss == null || !enabled) continue;

            RaidRarityTier tier = RaidBossEntityMarker.definitionId(boss)
                    .map(RaidDefinitionRegistry::get)
                    .map(RaidDefinition::rarityTier)
                    .orElse(null);
            if (tier == null) continue;

            if (RaidBossLookup.hasNearbyPlayer(server, boss, config.radiusBlocks())) {
                applyGlow(server, boss, tier);
            } else if (boss.hasEffect(MobEffects.GLOWING)) {
                boss.removeEffect(MobEffects.GLOWING);
            }
        }
    }

    /**
     * Whether a tracked boss should be dropped, given what the world could tell us about it.
     *
     * <p>A predicate rather than an inline condition because this exact rule has now been written
     * wrong three times in this codebase, in three different services. The trap is always the same:
     * a boss that does not resolve looks identical to a boss that no longer exists, and treating the
     * first as the second drops something that is still out there. Here the cost was a boss that
     * silently stopped glowing forever once its chunk had unloaded once, because registration only
     * happens at spawn and nothing re-adds it.
     *
     * @param resolved      the entity was found, i.e. its chunk and dimension are loaded
     * @param removed       it was found and reports itself removed
     * @param stillRaidBoss it was found and still carries the raid-boss marker
     */
    static boolean shouldUntrack(boolean resolved, boolean removed, boolean stillRaidBoss) {
        if (!resolved) return false;
        return removed || !stillRaidBoss;
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
    private static void untrack(MinecraftServer server, UUID bossId, PokemonEntity boss) {
        Scoreboard scoreboard = server.getScoreboard();
        String member = boss != null ? boss.getScoreboardName() : bossId.toString();
        // The single-argument form resolves the team itself and is a no-op when the member is on
        // none; the two-argument form throws if it guesses wrong.
        scoreboard.removePlayerFromTeam(member);
        if (boss != null && boss.hasEffect(MobEffects.GLOWING)) boss.removeEffect(MobEffects.GLOWING);
    }

    /**
     * Re-registers a raid boss that loads without ever having called {@link #register}.
     *
     * <p>register() only runs at spawn, so a boss whose raid was still going when the server last
     * stopped -- clean shutdown or a crash, either one -- comes back on restart with no entry in
     * TRACKED. Two consequences, both silent: the audit's {@code orphaned-glow-team-member} check
     * flags its (still legitimate) scoreboard membership as gone, and worse, {@link #onEntityUnloaded}
     * can never untrack it later either, because {@code TRACKED.remove} is a no-op for an id that was
     * never there -- so once this boss is genuinely destroyed, its team membership becomes permanent
     * growth in the world save, exactly the cost {@link #untrack} exists to avoid. Same fix as
     * {@code EncounterService.onEntityLoaded} for the analogous encounter-boss gap: bound to
     * ServerEntityEvents.ENTITY_LOAD rather than a boot-time sweep, because entity sections load
     * asynchronously after the server reports ready and a sweep run too early would miss this exact
     * case.
     *
     * <p>Unlike the encounter sibling fix, a freshly spawned boss here is NOT naturally excluded by
     * marker timing: RaidBossSpawner.spawnAt() tags the entity as a raid boss from inside the same
     * sendOut() call that triggers ENTITY_LOAD, before it gets the entity back to call this service's
     * own register(). Confirmed live: without the isSpawning() guard below, every ordinary spawn logged
     * a spurious "recovered from an untracked reload" line. RaidBossSpawner.isSpawning() suppresses it,
     * the same role RaidSpawnScheduler's own spawningTrackedBoss flag plays for its sibling check.
     */
    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        if (RaidBossSpawner.isSpawning()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!RaidBossEntityMarker.isRaidBoss(pokemon)) return;
        if (TRACKED.containsKey(pokemon.getUUID())) return;
        RaidLog.info("Re-registered a raid boss with the glow service after it (re)loaded untracked ({} in {})",
                pokemon.getUUID(), level.dimension().location());
        register(pokemon, level);
    }

    /** Untracks a boss the moment it is genuinely destroyed, whatever destroyed it. */
    public static void onEntityUnloaded(Entity entity, ServerLevel level) {
        if (TRACKED.isEmpty()) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        // Ordered cheapest-first: this fires for every entity leaving every chunk, and an ordinary
        // unload is rejected by one field read. UNLOADED_TO_CHUNK is not a destruction, and a boss
        // that merely unloaded must stay tracked so it glows again when its chunk comes back.
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (TRACKED.remove(pokemon.getUUID()) == null) return;
        untrack(level.getServer(), pokemon.getUUID(), pokemon);
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




}
