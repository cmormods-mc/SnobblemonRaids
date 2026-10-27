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
 * @param autoStart                whether a boss may start by itself in the window; false means it only starts by command or from another mod
 *                                 through {@code CobbleRaidsWorldBoss.start} (the Spooky Surge's top tier does this)
 * @param spawnLifetimeMinutes     how long one World Boss stays before it leaves undefeated
 * @param minPercentOfAverage      least damage, as a percent of the average per player who dealt any, that earns the defeat reward
 * @param rewardRaidPoints         Raid Points paid to each qualifying player
 * @param borderParticles          draw the zone as a wall of soul particles for players near its edge
 * @param borderViewDistance       how far along the wall, in blocks, a player is shown it
 * @param bossScale                size multiplier of the World Boss standing in the world (1 is the species' normal size)
 * @param duelBossScale            size multiplier of each duel's private copy of the boss
 * @param wallColor                the wall's tint as {@code #RRGGBB}
 * @param wallOpacityPercent       how opaque the tint is, 0 to 100
 * @param music                    the background music sent to players in the zone and in a duel
 * @param dome                     the glass dome drawn at the zone's edge
 * @param look                     what players see of the bosses inside the zone
 * @param night                    whether the boss may only start by itself at night
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
        boolean autoStart,
        int spawnLifetimeMinutes,
        float minPercentOfAverage,
        int rewardRaidPoints,
        boolean borderParticles,
        int borderViewDistance,
        float bossScale,
        float duelBossScale,
        String wallColor,
        int wallOpacityPercent,
        Music music,
        Dome dome,
        Look look,
        Night night) {

    /**
     * When the boss may start by itself: only at night when {@code only} is set. A command start ignores this. A dimension with a fixed time
     * (the Nether, the End) has no night, so it is never held back.
     *
     * @param only     start by itself only at night
     * @param fromTick the tick of the day the night begins (vanilla's dusk is 13000)
     * @param toTick   the tick it ends (vanilla's dawn is 23000); may be smaller than {@code fromTick} to run past midnight
     */
    public record Night(boolean only, int fromTick, int toTick) {
        public Night {
            require(fromTick >= 0 && fromTick < 24000, "spawn.night_from_tick", "must be 0 to 23999");
            require(toTick >= 0 && toTick < 24000, "spawn.night_to_tick", "must be 0 to 23999");
            require(fromTick != toTick, "spawn.night_to_tick", "must differ from night_from_tick");
        }

        public static Night defaults() {
            return new Night(true, 13000, 23000);
        }
    }

    /**
     * What players see of the bosses in the zone, so the fight feels like one against the big boss on the mountain.
     *
     * @param duelBossInvisible hide each duel's private copy of the boss (the showpiece stays): the battle still runs against it, but only the
     *                          big boss is drawn
     * @param noGlowInZone      no glowing outline on any raid boss standing inside an active World Boss zone, which would otherwise draw the copy
     *                          and the showpiece through terrain
     */
    public record Look(boolean duelBossInvisible, boolean noGlowInZone) {
        public static Look defaults() {
            return new Look(false, true);
        }
    }

    /**
     * The glass dome at the zone's edge (see {@link DomeGeometry}). It needs AscensionLib's resource pack, and while it is on it replaces the particle wall
     * ({@code visual.border_particles}); turn it off to get the particles back for servers without the pack.
     *
     * @param enabled draw the dome
     * @param height  how far it rises above the boss, in blocks (and sinks below it when full); it is as wide as the zone's radius
     * @param detail  how finely the dome is cut: 1 is 80 triangles, 2 is 320, 3 is 1280 for the whole sphere (half that for a hemisphere)
     * @param full    the whole sphere, centred on the boss, so a boss on a peak is not under a dome hanging in the air; false is a hemisphere
     *                standing on the ground
     */
    public record Dome(boolean enabled, int height, int detail, boolean full) {
        public Dome {
            require(height >= 10 && height <= 128, "dome.height", "must be 10 to 128");
            require(detail >= 1 && detail <= 3, "dome.detail", "must be 1 to 3");
        }

        public static Dome defaults() {
            return new Dome(true, 60, 2, true);
        }
    }

    /**
     * Zone and duel music. The server sends a sound id the client's resource pack must define (AscensionLib's pack ships two); a client without
     * the pack simply hears nothing. A sound cannot be asked how long it is, so the length is configured and the server restarts the track when it ends.
     *
     * @param enabled     send music at all
     * @param bossSound   sound event played to a player in a duel or its breather
     * @param bossSeconds length of that track
     * @param zoneSound   sound event played to a player inside the zone but not in a duel
     * @param zoneSeconds length of that track
     * @param volume      0 to 1; players still control it with their Music slider
     */
    public record Music(boolean enabled, String bossSound, int bossSeconds, String zoneSound, int zoneSeconds, float volume) {
        public Music {
            require(bossSound != null && net.minecraft.resources.ResourceLocation.tryParse(bossSound) != null, "music.boss_sound", "must be a sound id such as ascensionlib:world_event.boss");
            require(zoneSound != null && net.minecraft.resources.ResourceLocation.tryParse(zoneSound) != null, "music.zone_sound", "must be a sound id such as ascensionlib:world_event.zone");
            require(bossSeconds >= 5 && bossSeconds <= 3600, "music.boss_seconds", "must be 5 to 3600");
            require(zoneSeconds >= 5 && zoneSeconds <= 3600, "music.zone_seconds", "must be 5 to 3600");
            require(volume >= 0f && volume <= 1f, "music.volume", "must be 0 to 1");
        }

        public static Music defaults() {
            return new Music(true, "ascensionlib:world_event.boss", 180, "ascensionlib:world_event.zone", 148, 1.0f);
        }
    }

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
        require(bossScale >= 0.1f && bossScale <= 30f, "visual.boss_scale", "must be 0.1 to 30");
        require(duelBossScale >= 0.1f && duelBossScale <= 30f, "visual.duel_boss_scale", "must be 0.1 to 30");
        require(wallColor != null && wallColor.matches("#[0-9a-fA-F]{6}"), "visual.wall_color", "must look like #9A5CC6");
        require(wallOpacityPercent >= 0 && wallOpacityPercent <= 100, "visual.wall_opacity_percent", "must be 0 to 100");
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
                "minecraft:overworld", 300, 1500, 30, 30, true, 40,
                5f, 200, true, 40, 8f, 3f, "#9A5CC6", 50, Music.defaults(), Dome.defaults(), Look.defaults(), Night.defaults());
    }

    /**
     * What a fresh install of this build gets: {@link #defaults()} switched on, with the Halloween 2026 window (Eastern time: 31 Oct 00:00 EDT to the end
     * of 2 Nov EST, written in UTC because the clocks change on 1 Nov). A config file that already exists is never touched by this.
     */
    public static WorldBossConfig halloween2026() {
        JsonObject root = defaults().toJson();
        root.addProperty("enabled", true);
        JsonObject window = new JsonObject();
        window.addProperty("start", "2026-10-31T04:00:00Z");
        window.addProperty("end", "2026-11-03T05:00:00Z");
        root.add("window", window);
        return fromJson(root);
    }

    /** The wall's tint as an ARGB int: {@link #wallColor} with the alpha of {@link #wallOpacityPercent}. */
    public int wallColorArgb() {
        int rgb = Integer.parseInt(wallColor.substring(1), 16);
        int alpha = Math.round(wallOpacityPercent * 255f / 100f);
        return (alpha << 24) | rgb;
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
        JsonObject music = obj(root, "music");
        Music dm = d.music;
        JsonObject domeJson = obj(root, "dome");
        Dome dd = d.dome;
        Look dl = d.look;
        Night dn = d.night;
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
                bool(spawn, "spawn.auto_start", d.autoStart),
                (int) num(spawn, "spawn.lifetime_minutes", d.spawnLifetimeMinutes),
                num(reward, "reward.min_percent_of_average", d.minPercentOfAverage),
                (int) num(reward, "reward.raid_points", d.rewardRaidPoints),
                bool(visual, "visual.border_particles", d.borderParticles),
                (int) num(visual, "visual.view_distance", d.borderViewDistance),
                num(visual, "visual.boss_scale", d.bossScale),
                num(visual, "visual.duel_boss_scale", d.duelBossScale),
                str(visual, "visual.wall_color", d.wallColor),
                (int) num(visual, "visual.wall_opacity_percent", d.wallOpacityPercent),
                new Music(bool(music, "music.enabled", dm.enabled),
                        str(music, "music.boss_sound", dm.bossSound),
                        (int) num(music, "music.boss_seconds", dm.bossSeconds),
                        str(music, "music.zone_sound", dm.zoneSound),
                        (int) num(music, "music.zone_seconds", dm.zoneSeconds),
                        num(music, "music.volume", dm.volume)),
                new Dome(bool(domeJson, "dome.enabled", dd.enabled),
                        (int) num(domeJson, "dome.height", dd.height),
                        (int) num(domeJson, "dome.detail", dd.detail),
                        bool(domeJson, "dome.full", dd.full)),
                new Look(bool(visual, "visual.duel_boss_invisible", dl.duelBossInvisible),
                        bool(visual, "visual.no_glow_in_zone", dl.noGlowInZone)),
                new Night(bool(spawn, "spawn.night_only", dn.only),
                        (int) num(spawn, "spawn.night_from_tick", dn.fromTick),
                        (int) num(spawn, "spawn.night_to_tick", dn.toTick)));
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
        spawn.addProperty("auto_start", autoStart);
        spawn.addProperty("lifetime_minutes", spawnLifetimeMinutes);
        spawn.addProperty("night_only", night.only());
        spawn.addProperty("night_from_tick", night.fromTick());
        spawn.addProperty("night_to_tick", night.toTick());
        root.add("spawn", spawn);
        JsonObject reward = new JsonObject();
        reward.addProperty("min_percent_of_average", minPercentOfAverage);
        reward.addProperty("raid_points", rewardRaidPoints);
        root.add("reward", reward);
        JsonObject visual = new JsonObject();
        visual.addProperty("border_particles", borderParticles);
        visual.addProperty("view_distance", borderViewDistance);
        visual.addProperty("boss_scale", bossScale);
        visual.addProperty("duel_boss_scale", duelBossScale);
        visual.addProperty("wall_color", wallColor);
        visual.addProperty("wall_opacity_percent", wallOpacityPercent);
        visual.addProperty("duel_boss_invisible", look.duelBossInvisible());
        visual.addProperty("no_glow_in_zone", look.noGlowInZone());
        root.add("visual", visual);
        JsonObject musicJson = new JsonObject();
        musicJson.addProperty("enabled", music.enabled());
        musicJson.addProperty("boss_sound", music.bossSound());
        musicJson.addProperty("boss_seconds", music.bossSeconds());
        musicJson.addProperty("zone_sound", music.zoneSound());
        musicJson.addProperty("zone_seconds", music.zoneSeconds());
        musicJson.addProperty("volume", music.volume());
        root.add("music", musicJson);
        JsonObject domeJson = new JsonObject();
        domeJson.addProperty("enabled", dome.enabled());
        domeJson.addProperty("height", dome.height());
        domeJson.addProperty("detail", dome.detail());
        domeJson.addProperty("full", dome.full());
        root.add("dome", domeJson);
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
