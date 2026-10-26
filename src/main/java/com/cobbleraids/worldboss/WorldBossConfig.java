package com.cobbleraids.worldboss;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settings of the World Boss event system, read from {@code config/cobbleraids/world_boss.json}.
 *
 * <p>Pure: parsing takes a {@link JsonObject} and nothing else, so every rule and default can be
 * tested without a server. A missing key takes its default; a present key that is out of range
 * throws {@link IllegalArgumentException} naming the key, so a typo fails loudly at load rather
 * than becoming a boss nobody can beat. The system ships <b>off</b> ({@code enabled=false}): the
 * ordinary raid system is untouched unless an operator turns this one on.
 *
 * @param enabled                  master switch; when false no World Boss starts, by command or by window
 * @param raidDefinitions          ids of the {@code data/cobbleraids/raids} definitions a World Boss may be built
 *                                 from; each new boss picks one, avoiding a repeat of the last
 * @param windowStart              when the event opens by itself, or empty for command-only
 * @param windowEnd                when the event closes by itself, or empty for no automatic end
 * @param poolBase                 smallest World Pool
 * @param damagePerPlayerMinute    what one player is expected to deal per minute, breathers included
 * @param minutesPerPlayer         fight minutes each active player adds
 * @param maxMinutes               ceiling on the intended fight length, whatever the crowd
 * @param activeWindowSeconds      a player counts as active for this long after starting a duel
 * @param duelBossLevel            level of each duel's private boss
 * @param duelBossHealth           the duel pool: each duel's private boss health, and so the most one duel can deal
 * @param duelTimeLimitSeconds     a duel still running after this long is ended
 * @param breatherSeconds          pause between chained duels
 * @param allFaintedBreatherSeconds pause when every Pokemon in the player's party has fainted
 * @param chain                    whether the next duel starts by itself after the breather
 * @param radius                   how far from the boss a player may be and still duel
 * @param spawnDimension           dimension id the boss appears in
 * @param spawnMinDistance         nearest the boss appears to a randomly chosen online player
 * @param spawnMaxDistance         farthest
 * @param spawnAttempts            tries to find a surface position before giving up
 * @param spawnIntervalMinutes     gap between one World Boss ending and the next starting by itself in the window
 * @param spawnLifetimeMinutes     how long one World Boss stays before it leaves undefeated
 * @param minPercentOfAverage      least damage, as a percent of the average per player who dealt any, that earns the defeat reward
 * @param rewardRaidPoints         Raid Points paid to each qualifying player
 * @param borderParticles          draw the zone as a wall of soul particles for players near its edge
 * @param borderViewDistance       how far along the wall, in blocks, a player is shown it
 */
public record WorldBossConfig(
        boolean enabled,
        List<String> raidDefinitions,
        Optional<Instant> windowStart,
        Optional<Instant> windowEnd,
        float poolBase,
        float damagePerPlayerMinute,
        float minutesPerPlayer,
        float maxMinutes,
        int activeWindowSeconds,
        int duelBossLevel,
        long duelBossHealth,
        int duelTimeLimitSeconds,
        int breatherSeconds,
        int allFaintedBreatherSeconds,
        boolean chain,
        double radius,
        String spawnDimension,
        int spawnMinDistance,
        int spawnMaxDistance,
        int spawnAttempts,
        int spawnIntervalMinutes,
        int spawnLifetimeMinutes,
        float minPercentOfAverage,
        int rewardRaidPoints,
        boolean borderParticles,
        int borderViewDistance) {

    public static final String FILE_NAME = "world_boss.json";

    public WorldBossConfig {
        require(raidDefinitions != null && !raidDefinitions.isEmpty(), "raid_definitions", "must name at least one raid definition");
        for (String id : raidDefinitions) {
            require(id != null && !id.isBlank(), "raid_definitions", "must not contain a blank id");
        }
        raidDefinitions = List.copyOf(raidDefinitions);
        require(poolBase > 0f, "pool.base", "must be positive");
        require(damagePerPlayerMinute > 0f, "pool.damage_per_player_minute", "must be positive");
        require(minutesPerPlayer > 0f, "pool.minutes_per_player", "must be positive");
        require(maxMinutes > 0f, "pool.max_minutes", "must be positive");
        require(activeWindowSeconds >= 10, "pool.active_window_seconds", "must be at least 10");
        require(duelBossLevel >= 1 && duelBossLevel <= 100, "duel.boss_level", "must be 1 to 100");
        require(duelBossHealth >= 1, "duel.boss_health", "must be at least 1");
        require(duelTimeLimitSeconds >= 30, "duel.time_limit_seconds", "must be at least 30");
        require(breatherSeconds >= 0 && breatherSeconds <= 600, "duel.breather_seconds", "must be 0 to 600");
        require(allFaintedBreatherSeconds >= breatherSeconds && allFaintedBreatherSeconds <= 600,
                "duel.all_fainted_breather_seconds", "must be at least the breather, and at most 600");
        require(radius >= 8.0 && radius <= 1024.0, "radius", "must be 8 to 1024");
        require(spawnDimension != null && !spawnDimension.isBlank(), "spawn.dimension", "must name a dimension");
        require(spawnMinDistance >= 0, "spawn.min_distance", "must not be negative");
        require(spawnMaxDistance >= spawnMinDistance && spawnMaxDistance > 0, "spawn.max_distance",
                "must be positive and at least min_distance");
        require(spawnAttempts >= 1, "spawn.attempts", "must be at least 1");
        require(spawnIntervalMinutes >= 0, "spawn.interval_minutes", "must not be negative");
        require(spawnLifetimeMinutes >= 1, "spawn.lifetime_minutes", "must be at least 1");
        require(minPercentOfAverage >= 0f && minPercentOfAverage <= 100f, "reward.min_percent_of_average", "must be 0 to 100");
        require(rewardRaidPoints >= 0, "reward.raid_points", "must not be negative");
        require(borderViewDistance >= 16 && borderViewDistance <= 96, "visual.view_distance", "must be 16 to 96");
        if (windowStart.isPresent() && windowEnd.isPresent()) {
            require(windowEnd.get().isAfter(windowStart.get()), "window.end", "must be after window.start");
        }
    }

    /** What a fresh install gets: disabled, with numbers that are a starting point for a bot test. */
    public static WorldBossConfig defaults() {
        return new WorldBossConfig(false, List.of("cobbleraids:marshadow", "cobbleraids:gengar",
                "cobbleraids:chandelure", "cobbleraids:giratina", "cobbleraids:darkrai"), Optional.empty(), Optional.empty(),
                20_000f, 6_000f, 1f, 20f, 900,
                70, 3_000L, 240, 30, 45, true, 128.0,
                "minecraft:overworld", 300, 1500, 30, 30, 40,
                5f, 200, true, 40);
    }

    /** The least a player may deal, as a fraction of the average per player who dealt any, to earn the defeat reward. */
    public float minOfAverage() { return minPercentOfAverage / 100f; }

    public static WorldBossConfig fromJson(JsonObject root) {
        WorldBossConfig d = defaults();
        JsonObject window = obj(root, "window");
        JsonObject pool = obj(root, "pool");
        JsonObject duel = obj(root, "duel");
        JsonObject spawn = obj(root, "spawn");
        JsonObject reward = obj(root, "reward");
        JsonObject visual = obj(root, "visual");
        return new WorldBossConfig(
                bool(root, "enabled", d.enabled),
                definitions(root, d.raidDefinitions),
                instant(window, "window.start"),
                instant(window, "window.end"),
                num(pool, "pool.base", d.poolBase),
                num(pool, "pool.damage_per_player_minute", d.damagePerPlayerMinute),
                num(pool, "pool.minutes_per_player", d.minutesPerPlayer),
                num(pool, "pool.max_minutes", d.maxMinutes),
                (int) num(pool, "pool.active_window_seconds", d.activeWindowSeconds),
                (int) num(duel, "duel.boss_level", d.duelBossLevel),
                (long) num(duel, "duel.boss_health", d.duelBossHealth),
                (int) num(duel, "duel.time_limit_seconds", d.duelTimeLimitSeconds),
                (int) num(duel, "duel.breather_seconds", d.breatherSeconds),
                (int) num(duel, "duel.all_fainted_breather_seconds", d.allFaintedBreatherSeconds),
                bool(duel, "duel.chain", d.chain),
                num(root, "radius", (float) d.radius),
                str(spawn, "spawn.dimension", d.spawnDimension),
                (int) num(spawn, "spawn.min_distance", d.spawnMinDistance),
                (int) num(spawn, "spawn.max_distance", d.spawnMaxDistance),
                (int) num(spawn, "spawn.attempts", d.spawnAttempts),
                (int) num(spawn, "spawn.interval_minutes", d.spawnIntervalMinutes),
                (int) num(spawn, "spawn.lifetime_minutes", d.spawnLifetimeMinutes),
                num(reward, "reward.min_percent_of_average", d.minPercentOfAverage),
                (int) num(reward, "reward.raid_points", d.rewardRaidPoints),
                bool(visual, "visual.border_particles", d.borderParticles),
                (int) num(visual, "visual.view_distance", d.borderViewDistance));
    }

    /** The defaults written back as JSON, with the keys in the same grouping the parser reads. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        raidDefinitions.forEach(ids::add);
        root.add("raid_definitions", ids);
        root.addProperty("radius", radius);
        JsonObject window = new JsonObject();
        windowStart.ifPresent(value -> window.addProperty("start", value.toString()));
        windowEnd.ifPresent(value -> window.addProperty("end", value.toString()));
        root.add("window", window);
        JsonObject pool = new JsonObject();
        pool.addProperty("base", poolBase);
        pool.addProperty("damage_per_player_minute", damagePerPlayerMinute);
        pool.addProperty("minutes_per_player", minutesPerPlayer);
        pool.addProperty("max_minutes", maxMinutes);
        pool.addProperty("active_window_seconds", activeWindowSeconds);
        root.add("pool", pool);
        JsonObject duel = new JsonObject();
        duel.addProperty("boss_level", duelBossLevel);
        duel.addProperty("boss_health", duelBossHealth);
        duel.addProperty("time_limit_seconds", duelTimeLimitSeconds);
        duel.addProperty("breather_seconds", breatherSeconds);
        duel.addProperty("all_fainted_breather_seconds", allFaintedBreatherSeconds);
        duel.addProperty("chain", chain);
        root.add("duel", duel);
        JsonObject spawn = new JsonObject();
        spawn.addProperty("dimension", spawnDimension);
        spawn.addProperty("min_distance", spawnMinDistance);
        spawn.addProperty("max_distance", spawnMaxDistance);
        spawn.addProperty("attempts", spawnAttempts);
        spawn.addProperty("interval_minutes", spawnIntervalMinutes);
        spawn.addProperty("lifetime_minutes", spawnLifetimeMinutes);
        root.add("spawn", spawn);
        JsonObject reward = new JsonObject();
        reward.addProperty("min_percent_of_average", minPercentOfAverage);
        reward.addProperty("raid_points", rewardRaidPoints);
        root.add("reward", reward);
        JsonObject visual = new JsonObject();
        visual.addProperty("border_particles", borderParticles);
        visual.addProperty("view_distance", borderViewDistance);
        root.add("visual", visual);
        return root;
    }

    /** {@code raid_definitions} as a list; the older single {@code raid_definition} string is still read. */
    private static List<String> definitions(JsonObject root, List<String> fallback) {
        if (root.has("raid_definitions") && !root.get("raid_definitions").isJsonNull()) {
            if (!root.get("raid_definitions").isJsonArray()) {
                throw new IllegalArgumentException("raid_definitions must be a list of ids");
            }
            List<String> ids = new ArrayList<>();
            for (var element : root.getAsJsonArray("raid_definitions")) {
                try {
                    ids.add(element.getAsString());
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException("raid_definitions must be a list of text ids");
                }
            }
            return ids;
        }
        if (root.has("raid_definition") && !root.get("raid_definition").isJsonNull()) {
            return List.of(str(root, "raid_definition", fallback.get(0)));
        }
        return fallback;
    }

    private static JsonObject obj(JsonObject parent, String key) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) return new JsonObject();
        if (!parent.get(key).isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return parent.getAsJsonObject(key);
    }

    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static boolean bool(JsonObject parent, String path, boolean fallback) {
        String key = leaf(path);
        if (!parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try {
            return parent.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
    }

    private static String str(JsonObject parent, String path, String fallback) {
        String key = leaf(path);
        if (!parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try {
            return parent.get(key).getAsString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(path + " must be text");
        }
    }

    private static float num(JsonObject parent, String path, float fallback) {
        String key = leaf(path);
        if (!parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try {
            float value = parent.get(key).getAsFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException(path + " must be a finite number");
            return value;
        } catch (NumberFormatException | ClassCastException | IllegalStateException ex) {
            throw new IllegalArgumentException(path + " must be a number");
        }
    }

    private static Optional<Instant> instant(JsonObject parent, String path) {
        String key = leaf(path);
        if (!parent.has(key) || parent.get(key).isJsonNull()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(parent.get(key).getAsString()));
        } catch (DateTimeParseException | ClassCastException | IllegalStateException ex) {
            throw new IllegalArgumentException(path + " must be an ISO-8601 instant such as 2026-10-31T00:00:00Z");
        }
    }

    private static void require(boolean ok, String key, String rule) {
        if (!ok) throw new IllegalArgumentException(key + " " + rule);
    }
}
