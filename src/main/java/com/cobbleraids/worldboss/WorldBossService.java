package com.cobbleraids.worldboss;

import com.cobbleraids.RaidLog;
import com.cobbleraids.worldboss.arena.ArenaMarkers;
import com.cobbleraids.worldboss.arena.ArenaSpot;
import com.cobbleraids.api.encounter.EncounterListener;
import com.cobbleraids.api.encounter.EncounterPolicy;
import com.cobbleraids.api.encounter.EncounterRequest;
import com.cobbleraids.api.encounter.EncounterResult;
import com.cobbleraids.api.encounter.EncounterRules;
import com.cobbleraids.api.encounter.StartResult;
import com.cobbleraids.api.points.CobbleRaidsPoints;
import com.cobbleraids.api.worldboss.WorldBossListener;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.encounter.EncounterService;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.renown.RenownRequest;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobbleraids.spawn.RaidSpawnAnnouncementService;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The World Boss event: one shared health pool that every player's solo duel chips away at.
 *
 * <p>This is the only place that knows how the pieces fit. A duel is an ordinary owned encounter
 * (see {@link EncounterService}); the pool, scaling and chain rules are in the pure classes next
 * to this one; the state lives in {@link WorldBossState}. The service gathers facts from the
 * server (where players stand, what their party looks like), hands them to those rules, and acts
 * on the answer.
 *
 * <p>All entry points run on the server thread, as do the encounter callbacks, so the per-player
 * maps are not locked; they are concurrent maps only so a command reading them mid-tick is safe.
 *
 * <p>The ordinary raid system is untouched: nothing here runs unless {@link WorldBossConfig#enabled}
 * is set, and the only edits outside this package are the {@code onDamage} callback on
 * {@link EncounterListener}, the call that reports it, and the registration of this service.
 */
public final class WorldBossService {

    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("cobbleraids", "world_boss");

    /** Marks the standing boss, so a copy left in the world by an earlier session or event can be recognised and removed when it loads. */
    public static final String SHOWPIECE_TAG = "cobbleraids_world_boss_showpiece";

    private static final int BAR_UPDATE_TICKS = 10;
    private static final int SECOND = 20;
    private static final int FLUSH_TICKS = 100;
    /** How often the zone wall is redrawn; each pass sends a few particle packets only to players near the edge. */
    private static final int BORDER_TICKS = 8;
    private static final double BORDER_SPACING = 4.0;

    private static final CopyOnWriteArrayList<WorldBossListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** A running duel: which encounter it is and when it began (game ticks). */
    private record Duel(UUID encounterId, long startedTick) {}

    private static final Map<UUID, Duel> DUELS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> DUEL_PLAYER = new ConcurrentHashMap<>();
    /** Chained players waiting out a breather: the game tick their last duel ended. */
    private static final Map<UUID, Long> CHAIN = new ConcurrentHashMap<>();
    /** Wall-clock millis each player last started a duel; sizes the pool. */
    private static final Map<UUID, Long> LAST_DUEL_START = new ConcurrentHashMap<>();
    /** Players last seen inside the zone, so crossing the wall can be announced once. Only touched on the server thread. */
    private static final Map<UUID, Boolean> LAST_INSIDE = new HashMap<>();

    private static volatile MinecraftServer server;
    private static volatile WorldBossState state;
    private static volatile ServerBossEvent bar;
    private static volatile UUID showpieceId;
    private static volatile boolean barDirty;
    /** True while this class is creating its own showpiece, so the load hook does not mistake it for a leftover. */
    private static volatile boolean spawningShowpiece;
    private static volatile boolean stateDirty;
    private static long tickCounter;

    private WorldBossService() {}

    // ---- API for other mods ----

    public static void addListener(WorldBossListener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(WorldBossListener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isActive() {
        WorldBossState current = state;
        return current != null && current.active();
    }

    public static UUID activeBossId() {
        WorldBossState current = state;
        return current != null && current.active() ? current.bossId() : null;
    }

    /**
     * True when a point is inside the zone of a World Boss that is running and the config asks for no glow there. The raid glow service asks this
     * for every raid boss it tracks, so it must stay cheap: a few field reads and one distance test.
     */
    public static boolean suppressesGlowAt(ResourceLocation dimension, double x, double z) {
        WorldBossState current = state;
        if (current == null || !current.active() || current.pool() == null || current.pool().isDefeated()) return false;
        WorldBossConfig config = WorldBossConfigManager.get();
        if (!config.look().noGlowInZone() || !current.dimension().equals(dimension.toString())) return false;
        return WorldBossBorder.inside(current.x(), current.z(), WorldBossRules.zoneRadius(current, config), x, z);
    }

    public static boolean isShowpiece(Entity entity) {
        UUID id = showpieceId;
        return id != null && entity != null && id.equals(entity.getUUID());
    }

    // ---- lifecycle ----

    /** After the stale-boss sweep in RaidSpawnScheduler: resumes an event that survived a restart. */
    public static void onServerStarted(MinecraftServer started) {
        server = started;
        state = WorldBossState.get(started);
        showpieceId = null;
        WorldBossState current = state;
        if (!current.active() || current.pool() == null) return;
        WorldBossPool pool = current.pool();
        if (pool.isDefeated()) {
            RaidLog.info("World Boss {} was defeated before the restart; finishing its rewards.", current.bossId());
            finishDefeat(started);
            return;
        }
        RaidLog.info("Resuming World Boss {} ({}): pool {}/{}", current.bossId(), current.definitionId(),
                Math.round(pool.current()), Math.round(pool.max()));
        // No showpiece is tracked yet, so any boss at the spot is a stale copy of the one about to be recreated (a restart used to duplicate it).
        sweepLeftovers(started);
        spawnShowpiece(started);
        ensureBar(started);
    }

    public static void onServerStopping(MinecraftServer stopping) {
        flush(stopping);
        removeShowpiece(stopping);
        if (bar != null) bar.removeAllPlayers();
    }

    public static void onServerStopped() {
        DUELS.clear();
        DUEL_PLAYER.clear();
        CHAIN.clear();
        LAST_DUEL_START.clear();
        LAST_INSIDE.clear();
        WorldBossMusic.clear();
        WorldBossDome.reset();
        server = null;
        state = null;
        bar = null;
        showpieceId = null;
        barDirty = false;
        stateDirty = false;
        tickCounter = 0L;
    }

    /** Called every server tick; does its real work once a second. */
    public static void tick(MinecraftServer current) {
        if (state == null) return;
        tickCounter++;
        if (tickCounter % FLUSH_TICKS == 0 && stateDirty) flush(current);
        if (tickCounter % BAR_UPDATE_TICKS == 0 && barDirty) refreshBar(current);
        if (tickCounter % BORDER_TICKS == 0) {
            WorldBossState live = state;
            WorldBossConfig visual = WorldBossConfigManager.get();
            boolean running = live.active() && live.pool() != null && !live.pool().isDefeated();
            if (running) WorldBossDome.tick(current, live, visual);
            else WorldBossDome.clear(current);
            if (running && (visual.borderParticles() || visual.dome().enabled())) drawBorder(current, live, visual);
            else LAST_INSIDE.clear();
        }
        if (tickCounter % SECOND != 0) return;

        WorldBossConfig config = WorldBossConfigManager.get();
        long now = System.currentTimeMillis();
        WorldBossState s = state;

        if (!s.active()) {
            WorldBossMusic.stopAll(current);
            WorldBossRules.Window window = WorldBossRules.window(config, Instant.ofEpochMilli(now));
            if (WorldBossRules.shouldAutoStart(config, window, false, s.lastEndedAtMillis(), now) && isNightForSpawn(current, config)
                    && hasPlayerToPlaceAround(current, config)) {
                String problem = start(current, null);
                if (problem != null) RaidLog.warn("World Boss could not start by itself: {}", problem);
            }
            return;
        }
        if (s.pool() != null && s.pool().isDefeated()) {
            WorldBossMusic.stopAll(current);
            return;
        }
        if (s.endsAtMillis() > 0L && now >= s.endsAtMillis()) {
            end(current, "The World Boss has left.");
            return;
        }
        WorldBossMusic.tick(current, s, config, DUELS.keySet(), CHAIN.keySet());
        tickDuels(current, config);
        tickChains(current, config);
        refreshBar(current);
    }

    /**
     * Whether the automatic start may happen now: always when night-only is off or the spawn dimension has no day and night, otherwise only
     * while that dimension's clock is in the configured night. The command start does not ask this.
     */
    private static boolean isNightForSpawn(MinecraftServer current, WorldBossConfig config) {
        if (!config.night().only()) return true;
        ResourceLocation dimensionId = ResourceLocation.tryParse(config.spawnDimension());
        ServerLevel level = dimensionId == null ? null : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null || level.dimensionType().hasFixedTime()) return true;   // start() reports a missing dimension itself
        return WorldBossRules.isNight(level.getDayTime(), config.night().fromTick(), config.night().toTick());
    }

    /**
     * Whether someone is in the spawn dimension to place the boss around. Without this the automatic start fails every second, all night, with a
     * warning each time; it now waits quietly for the first player instead.
     */
    private static boolean hasPlayerToPlaceAround(MinecraftServer current, WorldBossConfig config) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(config.spawnDimension());
        ServerLevel level = dimensionId == null ? null : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        return level == null || randomPlayerIn(level) != null;   // a missing dimension is start()'s to report
    }

    // ---- starting and ending ----

    /**
     * Starts a World Boss.
     *
     * @param near a player to place the boss around, or null to pick a random online player
     * @return null on success, otherwise why it did not start
     */
    public static String start(MinecraftServer current, ServerPlayer near) {
        WorldBossConfig config = WorldBossConfigManager.get();
        if (!config.enabled()) return "the World Boss system is turned off (enabled=false in "
                + WorldBossConfigManager.path().getFileName() + ")";
        if (state == null) return "the World Boss system has not started yet";
        if (state.active()) return "a World Boss is already active";
        List<String> loaded = new ArrayList<>();
        for (String id : config.raidDefinitions()) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed != null && RaidDefinitionRegistry.get(parsed) != null) loaded.add(id);
            else RaidLog.warn("World Boss config names raid definition {}, which is not loaded; skipping it.", id);
        }
        String pickedId = WorldBossRules.pickDefinition(loaded, state.definitionId(), ThreadLocalRandom.current());
        if (pickedId == null) return "none of the configured raid definitions is loaded: " + config.raidDefinitions();
        RaidDefinition definition = RaidDefinitionRegistry.get(ResourceLocation.parse(pickedId));
        // With spawn markers placed in an arena, the boss stands at one of them; otherwise it appears at a random surface spot near a player.
        ArenaPick arenaPick = config.arena().enabled() ? pickArena(current) : null;
        if (arenaPick != null && arenaPick.problem() != null) return arenaPick.problem();
        boolean arenaMode = arenaPick != null;
        ServerLevel level;
        BlockPos spot;
        if (arenaMode) {
            level = arenaPick.level();
            spot = arenaPick.pos();
        } else {
            ResourceLocation dimensionId = ResourceLocation.tryParse(config.spawnDimension());
            level = dimensionId == null ? null : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            if (level == null) return "dimension " + config.spawnDimension() + " does not exist";
            ServerPlayer anchor = near != null && near.level() == level ? near : randomPlayerIn(level);
            if (anchor == null) return "nobody is online in " + config.spawnDimension() + " to place the boss around";
            spot = findLand(level, anchor.blockPosition(), config);
            if (spot == null) return "no suitable surface was found after " + config.spawnAttempts() + " attempts";
        }

        long now = System.currentTimeMillis();
        float pool = WorldBossScaling.poolSize(config.poolBase(), config.damagePerPlayerMinute(), 1,
                config.minutesPerPlayer(), config.maxMinutes());
        WorldBossState s = state;
        s.begin(UUID.randomUUID(), definition.id().toString(), level.dimension().location().toString(),
                spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, now, WorldBossRules.endMillis(config, now),
                new WorldBossPool(pool), arenaMode);
        DUELS.clear();
        DUEL_PLAYER.clear();
        CHAIN.clear();
        LAST_DUEL_START.clear();
        flush(current);
        try {
            spawnShowpiece(current);
        } catch (RuntimeException ex) {
            // The event still runs from its saved position; the boss just has no model until a restart.
            RaidLog.error("World Boss {} started but its showpiece could not be spawned", s.bossId(), ex);
        }
        ensureBar(current);
        announceStart(current, definition, level, spot, arenaMode);
        RaidLog.info("World Boss {} started{}: {} at {} {} {} in {}, pool {}", s.bossId(), arenaMode ? " at an arena marker" : "", definition.id(),
                spot.getX(), spot.getY(), spot.getZ(), level.dimension().location(), Math.round(pool));
        return null;
    }

    /** What {@link #stop} did: whether an event was running, and how many leftover bosses it removed at the boss's last spot. */
    public record StopResult(boolean wasActive, int swept) {}

    /**
     * Ends the active World Boss without a defeat, and removes every boss standing at its last spot: not only the showpiece this server is
     * tracking but copies an earlier run left behind (an old build, a crash, a restart that respawned the showpiece). Run it with nothing
     * active to clear leftovers from an event that already ended.
     */
    public static StopResult stop(MinecraftServer current) {
        if (state == null) return new StopResult(false, 0);
        if (state.active()) return new StopResult(true, end(current, "The World Boss has been called off."));
        return new StopResult(false, sweepLeftovers(current));
    }

    /** Ends the event; returns how many leftover bosses it swept up at the boss's spot besides the showpiece. */
    private static int end(MinecraftServer current, String message) {
        WorldBossState s = state;
        WorldBossPool pool = s.pool();
        UUID bossId = s.bossId();
        String definitionId = s.definitionId();
        Map<UUID, Float> damage = pool == null ? Map.of() : pool.contributions();
        float total = pool == null ? 0f : pool.max();
        abortDuels();
        s.finish(System.currentTimeMillis());
        flush(current);
        removeShowpiece(current);
        int swept = sweepLeftovers(current);
        for (UUID warped : new ArrayList<>(RETURN_TO.keySet())) {
            ServerPlayer visitor = current.getPlayerList().getPlayer(warped);
            if (visitor != null) say(visitor, "The fight is over. /cobbleraids worldboss return takes you back.", ChatFormatting.YELLOW);
        }
        clearBar();
        broadcast(current, Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
        for (WorldBossListener listener : LISTENERS) {
            RaidFaultBarrier.guard("worldboss:listener-ended",
                    () -> listener.onEnded(bossId, definitionId, damage, total));
        }
        CHAIN.clear();
        RaidLog.info("World Boss {} ended undefeated: {} damage of {}; {} leftover boss(es) swept", bossId,
                Math.round(pool == null ? 0f : pool.totalDamage()), Math.round(total), swept);
        return swept;
    }

    // ---- joining and duelling ----

    /** Starts a duel for the player and begins their chain. Returns null on success, else why not. */
    public static String join(ServerPlayer player) {
        WorldBossConfig config = WorldBossConfigManager.get();
        WorldBossState s = state;
        if (s == null || !s.active() || s.pool() == null) return "There is no World Boss right now.";
        if (s.pool().isDefeated() || s.pool().current() <= 0f) return "The World Boss has already fallen.";
        if (DUELS.containsKey(player.getUUID())) return "You are already in a duel.";
        String why = cannotDuel(player, s, config);
        if (why != null) return why;
        CHAIN.remove(player.getUUID());
        return startDuel(player, s, config);
    }

    /** Leaves the chain: no more automatic duels. Damage already dealt is kept. */
    public static boolean leave(ServerPlayer player) {
        return CHAIN.remove(player.getUUID()) != null;
    }

    private static String cannotDuel(ServerPlayer player, WorldBossState s, WorldBossConfig config) {
        if (!player.isAlive() || player.isSpectator()) return "You cannot duel right now.";
        if (!inRange(player, s, config)) return "You are too far from the World Boss (limit "
                + Math.round(WorldBossRules.zoneRadius(s, config)) + " blocks).";
        if (Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null) {
            return "Finish your current battle first.";
        }
        if (!hasFightingPokemon(player)) return "None of your Pokemon can fight. Heal them first.";
        return null;
    }

    private static String startDuel(ServerPlayer player, WorldBossState s, WorldBossConfig config) {
        ResourceLocation definitionId = ResourceLocation.tryParse(s.definitionId());
        if (definitionId == null) return "The World Boss definition is invalid.";
        long nowMillis = System.currentTimeMillis();
        LAST_DUEL_START.put(player.getUUID(), nowMillis);
        int active = WorldBossRules.activePlayers(LAST_DUEL_START, nowMillis, config.activeWindowSeconds() * 1000L);
        s.pool().growTo(WorldBossScaling.poolSize(config.poolBase(), config.damagePerPlayerMinute(), active,
                config.minutesPerPlayer(), config.maxMinutes()));
        s.touch();
        stateDirty = true;
        barDirty = true;

        UUID encounterId = UUID.randomUUID();
        Vec3 position = duelPosition(player);
        if (position == null) return "The duel cannot start from where you are standing.";
        EncounterPolicy policy = EncounterPolicy.none().withCarryover(true, true);
        EncounterRequest request = new EncounterRequest(OWNER, encounterId, List.of(player), definitionId,
                player.serverLevel(), position, config.duelBossLevel(), OptionalLong.of(config.duelBossHealth()),
                policy, EncounterRules.none());
        StartResult result;
        try {
            result = EncounterService.start(request, DUEL_LISTENER);
        } catch (RuntimeException ex) {
            RaidLog.error("World Boss duel for {} could not start", player.getGameProfile().getName(), ex);
            return "The duel could not start. Tell an administrator.";
        }
        if (result instanceof StartResult.Refused refused) return refused.reason();
        long gameTick = player.serverLevel().getGameTime();
        DUELS.put(player.getUUID(), new Duel(encounterId, gameTick));
        PokemonEntity duelBoss = EncounterService.bossOf(encounterId);
        if (duelBoss != null) duelBoss.getPokemon().setScaleModifier(config.duelBossScale());
        if (duelBoss != null && config.look().duelBossInvisible()) hideDuelBoss(duelBoss);
        // Written for the one case that matters: a duel that is followed by a frozen server leaves this line as the last thing it said.
        RaidLog.info("World Boss duel {}: player {} at ({}, {}, {}), boss asked for ({}, {}, {}) and now at {}, size x{}, party of {}",
                encounterId, player.getGameProfile().getName(), Math.round(player.getX()), Math.round(player.getY()), Math.round(player.getZ()),
                Math.round(position.x), Math.round(position.y), Math.round(position.z),
                duelBoss == null ? "?" : Math.round(duelBoss.getX()) + ", " + Math.round(duelBoss.getY()) + ", " + Math.round(duelBoss.getZ()),
                config.duelBossScale(), partySize(player));
        DUEL_PLAYER.put(encounterId, player.getUUID());
        say(player, "A duel against the World Boss begins!", ChatFormatting.LIGHT_PURPLE);
        return null;
    }

    /**
     * Where the duel's private boss stands: the first of {@link WorldBossDuelSpot#candidates} (6 blocks and more round the player, in front first)
     * that has loaded ground within a few blocks of the player's height and nothing solid or watery at its feet. Never the player's own spot: a boss
     * on top of the player makes Cobblemon divide by a zero distance, and the NaN that follows freezes the server thread in a block raycast
     * (found in two thread dumps). With no good ground at all (the player is flying, or in a cave) the nearest candidate at the player's own
     * height is used, which is still at least 6 blocks away.
     */
    private static Vec3 duelPosition(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        var spots = WorldBossDuelSpot.candidates(player.getX(), player.getZ(), look.x, look.z);
        for (double[] spot : spots) {
            BlockPos column = BlockPos.containing(spot[0], player.getY(), spot[1]);
            if (!level.hasChunkAt(column)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            if (Math.abs(y - player.getY()) > 6.0) continue;   // a cliff or a roof, not somewhere to stand
            BlockPos feet = new BlockPos(column.getX(), y, column.getZ());
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) continue;
            return new Vec3(spot[0], y, spot[1]);
        }
        double[] fallback = spots.isEmpty() ? null : spots.get(0);
        return fallback == null ? null : new Vec3(fallback[0], player.getY(), fallback[1]);
    }

    private static final EncounterListener DUEL_LISTENER = new EncounterListener() {
        @Override
        public void onDamage(UUID encounterId, UUID playerId, float applied) {
            strike(playerId, applied);
        }

        @Override
        public void onEnded(EncounterResult result) {
            duelEnded(result);
        }
    };

    private static void strike(UUID playerId, float applied) {
        WorldBossState s = state;
        MinecraftServer current = server;
        if (s == null || current == null || !s.active() || s.pool() == null) return;
        float dealt = s.pool().strike(playerId, applied);
        if (dealt <= 0f) return;
        s.touch();
        stateDirty = true;
        barDirty = true;
        if (s.pool().current() <= 0f) handleDefeat(current);
    }

    private static void duelEnded(EncounterResult result) {
        UUID playerId = DUEL_PLAYER.remove(result.encounterId());
        if (playerId == null) return;
        DUELS.remove(playerId);
        WorldBossState s = state;
        MinecraftServer current = server;
        if (s == null || current == null || !s.active()) return;
        ServerPlayer player = current.getPlayerList().getPlayer(playerId);
        if (s.pool() != null && s.pool().isDefeated()) return;
        if (player == null || !WorldBossConfigManager.get().chain()) return;
        CHAIN.put(playerId, player.serverLevel().getGameTime());
    }

    private static void tickDuels(MinecraftServer current, WorldBossConfig config) {
        long limitTicks = config.duelTimeLimitSeconds() * (long) SECOND;
        for (Map.Entry<UUID, Duel> entry : DUELS.entrySet()) {
            long now = current.overworld().getGameTime();
            if (now - entry.getValue().startedTick() > limitTicks) {
                EncounterService.abort(entry.getValue().encounterId());
            }
        }
    }

    private static void tickChains(MinecraftServer current, WorldBossConfig config) {
        WorldBossState s = state;
        boolean over = s.pool() == null || s.pool().isDefeated();
        for (Map.Entry<UUID, Long> entry : CHAIN.entrySet()) {
            UUID playerId = entry.getKey();
            ServerPlayer player = current.getPlayerList().getPlayer(playerId);
            boolean online = player != null;
            boolean fainted = online && !hasFightingPokemon(player);
            long now = online ? player.serverLevel().getGameTime() : 0L;
            int breather = WorldBossRules.breatherSeconds(config, fainted);
            long waited = online ? now - entry.getValue() : 0L;
            boolean elapsed = waited >= breather * (long) SECOND;
            WorldBossRules.Step step = WorldBossRules.nextStep(over, false, online,
                    online && inRange(player, s, config),
                    online && Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null,
                    online && !fainted, elapsed);
            switch (step) {
                case WAIT -> {
                    long left = Math.max(0L, breather - waited / SECOND);
                    player.displayClientMessage(Component.literal("Next duel in " + left + "s"
                            + (fainted ? " (heal your Pokemon!)" : "") + "  |  World Boss "
                            + percentLeft(s.pool()) + "% left").withStyle(ChatFormatting.LIGHT_PURPLE), true);
                }
                case START -> {
                    CHAIN.remove(playerId);
                    String problem = startDuel(player, s, config);
                    if (problem != null) say(player, problem, ChatFormatting.RED);
                }
                case FINISHED -> CHAIN.remove(playerId);
                case PAUSE_OFFLINE -> CHAIN.remove(playerId);
                case PAUSE_OUT_OF_RANGE -> pause(playerId, player, "You moved out of range of the World Boss.");
                case PAUSE_BUSY -> pause(playerId, player, "You are in a battle.");
                case PAUSE_NO_POKEMON -> pause(playerId, player, "None of your Pokemon can fight.");
                case PAUSE_OPTED_OUT -> CHAIN.remove(playerId);
            }
        }
    }

    private static void pause(UUID playerId, ServerPlayer player, String reason) {
        CHAIN.remove(playerId);
        if (player != null) say(player, reason + " Use /cr worldboss join to continue.", ChatFormatting.YELLOW);
    }

    // ---- defeat and rewards ----

    private static void handleDefeat(MinecraftServer current) {
        WorldBossState s = state;
        if (s == null || s.pool() == null) return;
        if (!s.pool().claimDefeat()) return;
        s.touch();
        flush(current);
        finishDefeat(current);
    }

    /**
     * Pays and announces a defeat. Safe to run again after a restart: Raid Points are guarded by a
     * flag saved first (at most once), and listeners by a flag saved after (at least once, which is
     * why the listener API names the boss id as an idempotency key).
     */
    private static void finishDefeat(MinecraftServer current) {
        WorldBossState s = state;
        WorldBossConfig config = WorldBossConfigManager.get();
        WorldBossPool pool = s.pool();
        Map<UUID, Float> damage = pool.contributions();
        float total = pool.max();

        if (!s.rewardsPaid()) {
            s.markRewardsPaid();
            flush(current);
            int paid = 0;
            float average = WorldBossRules.averageDamage(damage);
            for (Map.Entry<UUID, Float> entry : damage.entrySet()) {
                float share = total <= 0f ? 0f : entry.getValue() / total;
                if (!WorldBossRules.qualifies(entry.getValue(), average, config.minOfAverage())) continue;
                final UUID playerId = entry.getKey();
                final int amount = config.rewardRaidPoints();
                if (amount > 0) {
                    RaidFaultBarrier.guard("worldboss:award", () -> CobbleRaidsPoints.award(current, playerId, amount));
                }
                paid++;
                ServerPlayer online = current.getPlayerList().getPlayer(playerId);
                if (online != null) {
                    say(online, "You earned " + amount + " Raid Points for " + Math.round(share * 1000f) / 10f
                            + "% of the damage.", ChatFormatting.GREEN);
                }
            }
            RaidLog.info("World Boss {} defeated: {} player(s) paid {} Raid Points each", s.bossId(), paid,
                    config.rewardRaidPoints());
        }
        if (!s.defeatAnnounced()) {
            announceDefeat(current, damage, total);
            UUID bossId = s.bossId();
            String definitionId = s.definitionId();
            for (WorldBossListener listener : LISTENERS) {
                RaidFaultBarrier.guard("worldboss:listener-defeated",
                        () -> listener.onDefeated(bossId, definitionId, damage, total));
            }
            s.markDefeatAnnounced();
        }
        s.finish(System.currentTimeMillis());
        flush(current);
        removeShowpiece(current);
        sweepLeftovers(current);
        clearBar();
        CHAIN.clear();
    }

    private static void announceDefeat(MinecraftServer current, Map<UUID, Float> damage, float total) {
        List<Map.Entry<UUID, Float>> top = new ArrayList<>(damage.entrySet());
        top.sort(Map.Entry.<UUID, Float>comparingByValue(Comparator.reverseOrder()));
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            ServerPlayer player = current.getPlayerList().getPlayer(top.get(i).getKey());
            if (player == null) continue;
            if (!names.isEmpty()) names.append(", ");
            names.append(player.getGameProfile().getName());
        }
        var message = Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("The World Boss has fallen! ").withStyle(ChatFormatting.GREEN));
        if (!names.isEmpty()) {
            message.append(Component.literal("Top fighters online: " + names + ".").withStyle(ChatFormatting.YELLOW));
        }
        broadcast(current, message);
    }

    // ---- state, spawning, announcements ----

    private static void flush(MinecraftServer current) {
        WorldBossState s = state;
        if (s == null) return;
        try {
            s.flush(current);
            stateDirty = false;
        } catch (java.io.IOException ex) {
            RaidLog.error("Could not save the World Boss state", ex);
        }
    }

    private static void spawnShowpiece(MinecraftServer current) {
        WorldBossState s = state;
        ResourceLocation definitionId = ResourceLocation.tryParse(s.definitionId());
        RaidDefinition definition = definitionId == null ? null : RaidDefinitionRegistry.get(definitionId);
        ResourceLocation dimensionId = ResourceLocation.tryParse(s.dimension());
        ServerLevel level = dimensionId == null ? null
                : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (definition == null || level == null) return;
        // The chunk may not be loaded after a restart; spawning there needs it.
        level.getChunk(SectionPos.blockToSectionCoord((int) s.x()), SectionPos.blockToSectionCoord((int) s.z()),
                ChunkStatus.FULL, true);
        PokemonEntity boss;
        spawningShowpiece = true;
        try {
            boss = RaidBossSpawner.spawnAt(level, new Vec3(s.x(), s.y(), s.z()), definition, RenownRequest.NONE);
        } finally {
            spawningShowpiece = false;
        }
        boss.addTag(SHOWPIECE_TAG);
        boss.getPokemon().setScaleModifier(WorldBossConfigManager.get().bossScale());
        showpieceId = boss.getUUID();
    }

    /**
     * Removes a standing boss this server no longer owns when its chunk loads. The startup sweep in RaidSpawnScheduler only sees entities in chunks
     * that are loaded at that moment, and a boss is saved with its chunk, so one whose event ended (or whose server stopped) while nobody was near it
     * would otherwise reappear later beside the new boss. The live boss keeps its own id and is left alone.
     */
    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        if (spawningShowpiece || !(entity instanceof PokemonEntity pokemon) || !pokemon.getTags().contains(SHOWPIECE_TAG)) return;
        if (isShowpiece(pokemon)) return;
        RaidLog.info("Removed a World Boss left over from an earlier event ({} in {})", pokemon.getUUID(), level.dimension().location());
        com.cobbleraids.spawn.RaidDeferredDiscard.discardSoon(pokemon, level);
    }

    /** Applies the current visual settings (the boss's size) to the standing boss; the wall reads them on every pass anyway. */
    public static void refreshVisuals(MinecraftServer current) {
        UUID id = showpieceId;
        if (id == null) return;
        float scale = WorldBossConfigManager.get().bossScale();
        for (ServerLevel level : current.getAllLevels()) {
            if (level.getEntity(id) instanceof PokemonEntity boss && !boss.isRemoved()) boss.getPokemon().setScaleModifier(scale);
        }
    }

    private static void removeShowpiece(MinecraftServer current) {
        UUID id = showpieceId;
        showpieceId = null;
        if (id == null) return;
        for (ServerLevel level : current.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) entity.discard();
        }
    }

    /** How far from the boss's spot (blocks, each way) a raid boss counts as a leftover of it. */
    private static final double LEFTOVER_RADIUS = 6.0;

    /**
     * Removes every raid boss standing within {@link #LEFTOVER_RADIUS} of where the last World Boss stood, loading the chunks there if need be.
     * {@link #removeShowpiece} only knows the one it is tracking; a copy from an earlier build (before the showpiece had a tag), a showpiece a
     * restart duplicated, or one whose chunk was unloaded when the event ended is invisible to it, and would stay through every restart.
     * Bosses elsewhere are not touched, so ordinary raids are safe.
     *
     * @return how many were removed
     */
    private static int sweepLeftovers(MinecraftServer current) {
        WorldBossState s = state;
        if (s == null || s.definitionId().isEmpty()) return 0;   // no World Boss ever started here
        ResourceLocation dimensionId = ResourceLocation.tryParse(s.dimension());
        ServerLevel level = dimensionId == null ? null
                : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) return 0;
        int chunkX = SectionPos.blockToSectionCoord((int) s.x());
        int chunkZ = SectionPos.blockToSectionCoord((int) s.z());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, true);
        }
        var area = new net.minecraft.world.phys.AABB(s.x() - LEFTOVER_RADIUS, level.getMinBuildHeight(), s.z() - LEFTOVER_RADIUS,
                s.x() + LEFTOVER_RADIUS, level.getMaxBuildHeight(), s.z() + LEFTOVER_RADIUS);
        int removed = 0;
        for (PokemonEntity boss : level.getEntitiesOfClass(PokemonEntity.class, area, com.cobbleraids.spawn.RaidBossEntityMarker::isRaidBoss)) {
            boss.discard();
            removed++;
        }
        if (removed > 0) RaidLog.info("Swept {} leftover boss(es) at the World Boss spot ({}, {}, {})", removed,
                Math.round(s.x()), Math.round(s.y()), Math.round(s.z()));
        return removed;
    }

    /**
     * Hides a duel's private copy so only the showpiece is drawn. It has to be a real Invisibility effect: a living entity with no such effect
     * resets its own invisible flag every tick (LivingEntity.updateInvisibilityStatus), so {@code setInvisible(true)} lasts a single tick. The
     * effect is infinite, with no particles and no icon, and ends with the copy.
     */
    /**
     * Puts the hidden look back on a duel copy after Cobblemon wipes its effects. Called from RaidBossAttributeResetMixin, which runs on the
     * entity's first tick and whenever its level changes, and removeAllEffects() there takes the Invisibility with it.
     */
    public static void restoreDuelLook(PokemonEntity boss) {
        if (!com.cobbleraids.spawn.RaidBossEntityMarker.isOwnedBy(boss, OWNER)) return;
        if (WorldBossConfigManager.get().look().duelBossInvisible()) hideDuelBoss(boss);
    }

    private static void hideDuelBoss(PokemonEntity boss) {
        boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,
                net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
    }

    private static void abortDuels() {
        for (Duel duel : List.copyOf(DUELS.values())) EncounterService.abort(duel.encounterId());
        DUELS.clear();
        DUEL_PLAYER.clear();
    }

    private static ServerPlayer randomPlayerIn(ServerLevel level) {
        List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator()).toList();
        return players.isEmpty() ? null : players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }

    private static BlockPos findLand(ServerLevel level, BlockPos center, WorldBossConfig config) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double min = config.spawnMinDistance();
        double max = config.spawnMaxDistance();
        for (int attempt = 0; attempt < config.spawnAttempts(); attempt++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double radius = min >= max ? max : Math.sqrt(random.nextDouble(min * min, max * max));
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getSeaLevel(), z))) continue;
            // Loads (and if needed generates) the chunk: the boss is placed far from every player.
            level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z),
                    ChunkStatus.FULL, true);
            BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (isSimpleSurface(level, candidate)) return candidate;
        }
        return null;
    }

    private static boolean isSimpleSurface(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockPos above = pos.above();
        var ground = level.getBlockState(below);
        if (ground.isAir() || ground.getCollisionShape(level, below).isEmpty()) return false;
        if (!ground.getFluidState().isEmpty()) return false;
        var feet = level.getBlockState(pos);
        var head = level.getBlockState(above);
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        return feet.getCollisionShape(level, pos).isEmpty() && head.getCollisionShape(level, above).isEmpty();
    }

    private static void announceStart(MinecraftServer current, RaidDefinition definition, ServerLevel level, BlockPos spot, boolean arena) {
        var species = definition.species();
        var message = Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("A World Boss, ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(speciesName(species)).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (arena) {
            message.append(Component.literal(", has appeared in the arena! Warp in, right-click it to duel, together we bring it down. ")
                            .withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("[Click to warp]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cobbleraids worldboss warp"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Teleports you to the arena. /cobbleraids worldboss return takes you back.")))));
        } else {
            message.append(Component.literal(", has appeared! Near X " + WorldBossRules.coordinateHint(spot.getX())
                            + ", Z " + WorldBossRules.coordinateHint(spot.getZ()) + " ("
                            + level.dimension().location().getPath() + "). Right-click it to duel, together we bring it down.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        broadcast(current, message);
    }

    // ---- the arena: choosing the marker, warping in and back ----

    /** The marker the boss starts at, or why there is none. A null pick means "no markers placed": use the open-world placement. */
    private record ArenaPick(ServerLevel level, BlockPos pos, String problem) {}

    private static ArenaPick pickArena(MinecraftServer current) {
        List<ArenaMarkers.Spot> spots = new ArrayList<>(ArenaMarkers.get(current).list(ArenaMarkers.Kind.SPAWN));
        if (spots.isEmpty()) return null;
        java.util.Collections.shuffle(spots);
        for (ArenaMarkers.Spot spot : spots) {
            ResourceLocation id = ResourceLocation.tryParse(spot.dimension());
            ServerLevel level = id == null ? null : current.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) return new ArenaPick(level, spot.pos(), null);
        }
        return new ArenaPick(null, null, "the arena's spawn markers are in a dimension that is not loaded (" + spots.get(0).dimension() + ")");
    }

    /** Where a player was before warping in, so {@link #returnHome} can send them back. Server thread only; lost on restart. */
    private record Origin(ResourceKey<net.minecraft.world.level.Level> dimension, double x, double y, double z, float yaw, float pitch) {}

    private static final Map<UUID, Origin> RETURN_TO = new HashMap<>();

    /** Teleports the player into the arena. Returns null on success, else why not. */
    public static String warp(ServerPlayer player) {
        WorldBossConfig config = WorldBossConfigManager.get();
        WorldBossState s = state;
        if (s == null || !s.active() || s.pool() == null) return "There is no World Boss right now.";
        if (!s.arena()) return "This World Boss is not in an arena; walk to it.";
        if (s.pool().isDefeated() || s.pool().current() <= 0f) return "The World Boss has already fallen.";
        if (!player.isAlive() || player.isSpectator()) return "You cannot warp right now.";
        if (DUELS.containsKey(player.getUUID())) return "You are in a duel.";
        if (Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null) return "Finish your current battle first.";
        if (inRange(player, s, config)) return "You are already in the arena.";
        ResourceLocation dimensionId = ResourceLocation.tryParse(s.dimension());
        ServerLevel level = dimensionId == null || server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) return "The arena's dimension is not loaded.";
        BlockPos boss = BlockPos.containing(s.x(), s.y(), s.z());
        BlockPos target = arenaEntrance(level, boss);
        if (target == null) return "No safe place to arrive was found in the arena. Tell an administrator.";
        RETURN_TO.put(player.getUUID(), new Origin(player.level().dimension(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        double dx = boss.getX() - target.getX(), dz = boss.getZ() - target.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, yaw, 0f);
        say(player, "You are in the arena. Right-click the boss to duel. /cobbleraids worldboss return takes you back.", ChatFormatting.LIGHT_PURPLE);
        return null;
    }

    /** An entrance marker in the boss's dimension if any is placed, otherwise the nearest standable spot on a ring round the boss. */
    private static BlockPos arenaEntrance(ServerLevel level, BlockPos boss) {
        String dimension = level.dimension().location().toString();
        List<BlockPos> entrances = new ArrayList<>();
        for (ArenaMarkers.Spot spot : ArenaMarkers.get(level.getServer()).list(ArenaMarkers.Kind.ENTRANCE)) {
            if (spot.dimension().equals(dimension)) entrances.add(spot.pos());
        }
        if (!entrances.isEmpty()) return entrances.get(ThreadLocalRandom.current().nextInt(entrances.size()));
        return ArenaSpot.near(boss, new int[] {14, 18, 22, 10}, 6,
                pos -> level.getBlockState(pos).isSolid(), pos -> level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()).orElse(null);
    }

    /** Sends a player who warped in back to where they were (or to the world spawn if that is unknown). Returns null on success. */
    public static String returnHome(ServerPlayer player) {
        if (server == null) return "The server is not ready.";
        if (!player.isAlive() || player.isSpectator()) return "You cannot do that right now.";
        if (DUELS.containsKey(player.getUUID())) return "Finish your duel first.";
        if (Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null) return "Finish your current battle first.";
        Origin origin = RETURN_TO.remove(player.getUUID());
        if (origin == null) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), 0f);
            return null;
        }
        ServerLevel level = server.getLevel(origin.dimension());
        if (level == null) level = server.overworld();
        player.teleportTo(level, origin.x(), origin.y(), origin.z(), origin.yaw(), origin.pitch());
        return null;
    }

    private static String speciesName(ResourceLocation species) {
        String path = species.getPath();
        return path.isEmpty() ? path : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    private static void broadcast(MinecraftServer current, Component message) {
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            if (!RaidSpawnAnnouncementService.isMuted(player)) player.sendSystemMessage(message);
        }
    }

    private static void say(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color)));
    }

    // ---- the zone wall ----

    /**
     * Draws the edge of the zone as a wall of soul particles for each player near it, and says once when a player crosses it. The wall is the
     * circle {@link WorldBossConfig#radius} blocks round the boss, the same test a duel needs, so walking through it is walking into range.
     * Particles go only to the player who is near the wall (one packet per column), alternating two kinds so a pass stays cheap.
     */
    private static void drawBorder(MinecraftServer current, WorldBossState s, WorldBossConfig config) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(s.dimension());
        if (dimensionId == null) return;
        boolean flame = (tickCounter / BORDER_TICKS) % 2 == 0;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (!player.level().dimension().location().equals(dimensionId)) {
                LAST_INSIDE.remove(player.getUUID());
                continue;
            }
            double px = player.getX();
            double pz = player.getZ();
            var columns = WorldBossBorder.columns(s.x(), s.z(), WorldBossRules.zoneRadius(s, config), px, pz, config.borderViewDistance(), BORDER_SPACING);
            boolean inside = WorldBossBorder.inside(s.x(), s.z(), WorldBossRules.zoneRadius(s, config), px, pz);
            Boolean was = LAST_INSIDE.put(player.getUUID(), inside);
            if (was != null && was != inside) {
                if (config.dome().enabled()) WorldBossDome.ripple(player, inside);
                player.displayClientMessage(Component.literal(inside
                        ? "You entered the World Boss zone. Right-click the boss, or /cr worldboss join."
                        : "You left the World Boss zone.")
                        .withStyle(inside ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY), true);
            }
            if (columns.isEmpty() || !config.borderParticles() || config.dome().enabled()) continue;   // the dome replaces the particle wall
            ServerLevel level = player.serverLevel();
            double y = player.getY() + 4.0;
            // The potion swirl takes an ARGB colour whose alpha is honoured, which is what makes a translucent tint possible (dust has no alpha).
            var tint = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, config.wallColorArgb());
            for (double[] column : columns) {
                level.sendParticles(player, tint, true, column[0], y, column[1], flame ? 4 : 3, 0.25, 7.0, 0.25, 0.0);
            }
        }
        LAST_INSIDE.keySet().retainAll(online);
    }

    // ---- the boss bar ----

    private static void ensureBar(MinecraftServer current) {
        if (bar == null) {
            // PINK with the 20-notch overlay is the slot AscensionLib's resource pack reskins (tools/bossbar_art.py): nothing else uses PINK, so
            // no other bar changes. A client without the pack just sees a vanilla pink bar with a tick every 5 percent.
            bar = new ServerBossEvent(Component.literal("World Boss"), BossEvent.BossBarColor.PINK,
                    BossEvent.BossBarOverlay.NOTCHED_20);
        }
        refreshBar(current);
    }

    private static void clearBar() {
        ServerBossEvent current = bar;
        if (current != null) current.removeAllPlayers();
        bar = null;
    }

    private static void refreshBar(MinecraftServer current) {
        ServerBossEvent event = bar;
        WorldBossState s = state;
        if (event == null || s == null || s.pool() == null) return;
        barDirty = false;
        WorldBossPool pool = s.pool();
        WorldBossConfig config = WorldBossConfigManager.get();
        event.setProgress(pool.max() <= 0f ? 0f : Math.max(0f, Math.min(1f, pool.current() / pool.max())));
        Set<UUID> fighting = new HashSet<>(DUELS.keySet());
        fighting.addAll(CHAIN.keySet());
        long minutesLeft = s.endsAtMillis() > 0L ? Math.max(0L, (s.endsAtMillis() - System.currentTimeMillis() + 59_999L) / 60_000L) : -1L;
        event.setName(WorldBossBarText.build(speciesName(ResourceLocation.parse(s.definitionId())), pool.current(), pool.max(),
                percentLeft(pool), fighting.size(), minutesLeft));
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            boolean show = !pool.isDefeated() && (DUELS.containsKey(player.getUUID())
                    || CHAIN.containsKey(player.getUUID()) || inRange(player, s, config));
            if (show) event.addPlayer(player);
            else event.removePlayer(player);
        }
    }

    private static int percentLeft(WorldBossPool pool) {
        if (pool == null || pool.max() <= 0f) return 0;
        return Math.round(100f * pool.current() / pool.max());
    }

    // ---- facts about a player ----

    private static boolean inRange(ServerPlayer player, WorldBossState s, WorldBossConfig config) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(s.dimension());
        if (dimensionId == null || !player.level().dimension().location().equals(dimensionId)) return false;
        double dx = player.getX() - s.x();
        double dz = player.getZ() - s.z();
        double radius = WorldBossRules.zoneRadius(s, config);
        return dx * dx + dz * dz <= radius * radius;
    }

    private static int partySize(ServerPlayer player) {
        int size = 0;
        for (Pokemon ignored : Cobblemon.INSTANCE.getStorage().getParty(player)) size++;
        return size;
    }

    private static boolean hasFightingPokemon(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (!pokemon.isFainted()) return true;
        }
        return false;
    }

    // ---- reads for commands ----

    /** One line per fact about the current event, for {@code /cr worldboss status}. */
    public static List<String> status() {
        WorldBossState s = state;
        List<String> lines = new ArrayList<>();
        if (s == null) {
            lines.add("The World Boss system has not started.");
            return lines;
        }
        WorldBossConfig config = WorldBossConfigManager.get();
        lines.add("Enabled: " + config.enabled() + ", window: "
                + WorldBossRules.window(config, Instant.now()).name().toLowerCase());
        if (!s.active() || s.pool() == null) {
            lines.add("No World Boss is active.");
            return lines;
        }
        WorldBossPool pool = s.pool();
        long minutesLeft = Math.max(0L, (s.endsAtMillis() - System.currentTimeMillis()) / 60_000L);
        lines.add("Boss " + s.definitionId() + " (" + s.bossId() + ")");
        lines.add("Pool " + Math.round(pool.current()) + " / " + Math.round(pool.max()) + " ("
                + percentLeft(pool) + "% left)");
        lines.add("At about X " + Math.round(s.x()) + ", Z " + Math.round(s.z()) + " in " + s.dimension()
                + ", leaves in " + minutesLeft + " min");
        lines.add("Players who hit it: " + pool.contributions().size() + ", duelling now: " + DUELS.size()
                + ", chained: " + CHAIN.size());
        return lines;
    }

    /** A player's own damage and share, or null when there is no event. */
    public static String myShare(UUID playerId) {
        WorldBossState s = state;
        if (s == null || s.pool() == null || !s.active()) return null;
        return Math.round(s.pool().contributionOf(playerId)) + " damage ("
                + Math.round(s.pool().shareOf(playerId) * 1000f) / 10f + "% of the pool so far)";
    }
}
