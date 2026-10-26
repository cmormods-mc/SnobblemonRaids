package com.cobbleraids.worldboss;

import com.cobbleraids.RaidLog;
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
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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

    private static final int BAR_UPDATE_TICKS = 10;
    private static final int SECOND = 20;
    private static final int FLUSH_TICKS = 100;

    private static final CopyOnWriteArrayList<WorldBossListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** A running duel: which encounter it is and when it began (game ticks). */
    private record Duel(UUID encounterId, long startedTick) {}

    private static final Map<UUID, Duel> DUELS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> DUEL_PLAYER = new ConcurrentHashMap<>();
    /** Chained players waiting out a breather: the game tick their last duel ended. */
    private static final Map<UUID, Long> CHAIN = new ConcurrentHashMap<>();
    /** Wall-clock millis each player last started a duel; sizes the pool. */
    private static final Map<UUID, Long> LAST_DUEL_START = new ConcurrentHashMap<>();

    private static volatile MinecraftServer server;
    private static volatile WorldBossState state;
    private static volatile ServerBossEvent bar;
    private static volatile UUID showpieceId;
    private static volatile boolean barDirty;
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
        if (tickCounter % SECOND != 0) return;

        WorldBossConfig config = WorldBossConfigManager.get();
        long now = System.currentTimeMillis();
        WorldBossState s = state;

        if (!s.active()) {
            WorldBossRules.Window window = WorldBossRules.window(config, Instant.ofEpochMilli(now));
            if (WorldBossRules.shouldAutoStart(config, window, false, s.lastEndedAtMillis(), now)) {
                String problem = start(current, null);
                if (problem != null) RaidLog.warn("World Boss could not start by itself: {}", problem);
            }
            return;
        }
        if (s.pool() != null && s.pool().isDefeated()) return;
        if (s.endsAtMillis() > 0L && now >= s.endsAtMillis()) {
            end(current, "The World Boss has left.");
            return;
        }
        tickDuels(current, config);
        tickChains(current, config);
        refreshBar(current);
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
        ResourceLocation dimensionId = ResourceLocation.tryParse(config.spawnDimension());
        ServerLevel level = dimensionId == null ? null
                : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) return "dimension " + config.spawnDimension() + " does not exist";

        ServerPlayer anchor = near != null && near.level() == level ? near : randomPlayerIn(level);
        if (anchor == null) return "nobody is online in " + config.spawnDimension() + " to place the boss around";
        BlockPos spot = findLand(level, anchor.blockPosition(), config);
        if (spot == null) return "no suitable surface was found after " + config.spawnAttempts() + " attempts";

        long now = System.currentTimeMillis();
        float pool = WorldBossScaling.poolSize(config.poolBase(), config.damagePerPlayerMinute(), 1,
                config.minutesPerPlayer(), config.maxMinutes());
        WorldBossState s = state;
        s.begin(UUID.randomUUID(), definition.id().toString(), config.spawnDimension(),
                spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, now, WorldBossRules.endMillis(config, now),
                new WorldBossPool(pool));
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
        announceStart(current, definition, level, spot);
        RaidLog.info("World Boss {} started: {} at {} {} {} in {}, pool {}", s.bossId(), definition.id(),
                spot.getX(), spot.getY(), spot.getZ(), config.spawnDimension(), Math.round(pool));
        return null;
    }

    /** Ends the active World Boss without a defeat. Returns false when none is active. */
    public static boolean stop(MinecraftServer current) {
        if (state == null || !state.active()) return false;
        end(current, "The World Boss has been called off.");
        return true;
    }

    private static void end(MinecraftServer current, String message) {
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
        clearBar();
        broadcast(current, Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
        for (WorldBossListener listener : LISTENERS) {
            RaidFaultBarrier.guard("worldboss:listener-ended",
                    () -> listener.onEnded(bossId, definitionId, damage, total));
        }
        CHAIN.clear();
        RaidLog.info("World Boss {} ended undefeated: {} damage of {}", bossId,
                Math.round(pool == null ? 0f : pool.totalDamage()), Math.round(total));
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
                + Math.round(config.radius()) + " blocks).";
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
        DUEL_PLAYER.put(encounterId, player.getUUID());
        say(player, "A duel against the World Boss begins!", ChatFormatting.LIGHT_PURPLE);
        return null;
    }

    private static Vec3 duelPosition(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        Vec3 direction = flat.lengthSqr() < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
        Vec3 candidate = player.position().add(direction.scale(5.0));
        BlockPos feet = BlockPos.containing(candidate);
        ServerLevel level = player.serverLevel();
        boolean free = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
        return free ? candidate : player.position();
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
        PokemonEntity boss = RaidBossSpawner.spawnAt(level, new Vec3(s.x(), s.y(), s.z()), definition,
                RenownRequest.NONE);
        showpieceId = boss.getUUID();
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

    private static void announceStart(MinecraftServer current, RaidDefinition definition, ServerLevel level, BlockPos spot) {
        var species = definition.species();
        var message = Component.literal("[World Boss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("A World Boss, ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(speciesName(species)).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(", has appeared! Near X " + WorldBossRules.coordinateHint(spot.getX())
                        + ", Z " + WorldBossRules.coordinateHint(spot.getZ()) + " ("
                        + level.dimension().location().getPath() + "). Right-click it to duel, together we bring it down.")
                        .withStyle(ChatFormatting.YELLOW));
        broadcast(current, message);
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

    // ---- the boss bar ----

    private static void ensureBar(MinecraftServer current) {
        if (bar == null) {
            bar = new ServerBossEvent(Component.literal("World Boss"), BossEvent.BossBarColor.PURPLE,
                    BossEvent.BossBarOverlay.PROGRESS);
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
        event.setName(Component.literal(speciesName(ResourceLocation.parse(s.definitionId())) + " - "
                + Math.round(pool.current()) + " / " + Math.round(pool.max())
                + "  (" + percentLeft(pool) + "%)"));
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
        return dx * dx + dz * dz <= config.radius() * config.radius();
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
