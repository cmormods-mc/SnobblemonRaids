package com.cobbleraids.worldboss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The World Boss decisions that need no server: the event window, who counts as active, who
 * earns the reward, and what a chained player's next step is.
 *
 * <p>Kept apart from {@link WorldBossService} so each rule is a small pure function with a test,
 * and the service only has to gather the facts (is the player in range, is the party fainted) and
 * act on the answer.
 */
public final class WorldBossRules {

    private WorldBossRules() {}

    /** Where "now" sits relative to the configured window. */
    public enum Window {
        /** No window is configured: the event only starts and stops by command. */
        MANUAL,
        BEFORE,
        OPEN,
        AFTER
    }

    public static Window window(WorldBossConfig config, Instant now) {
        if (config.windowStart().isEmpty() && config.windowEnd().isEmpty()) return Window.MANUAL;
        if (config.windowStart().isPresent() && now.isBefore(config.windowStart().get())) return Window.BEFORE;
        if (config.windowEnd().isPresent() && !now.isBefore(config.windowEnd().get())) return Window.AFTER;
        return Window.OPEN;
    }

    /** Seconds to wait between two chained duels. Fainted parties wait longer. */
    public static int breatherSeconds(WorldBossConfig config, boolean everyPokemonFainted) {
        return everyPokemonFainted ? config.allFaintedBreatherSeconds() : config.breatherSeconds();
    }

    /**
     * How many players started a duel recently enough to count towards the pool's size.
     *
     * @param lastDuelStartMillis when each player last started a duel, in the same clock as {@code nowMillis}
     */
    public static int activePlayers(Map<UUID, Long> lastDuelStartMillis, long nowMillis, long windowMillis) {
        int active = 0;
        for (long started : lastDuelStartMillis.values()) {
            if (nowMillis - started <= windowMillis) active++;
        }
        return active;
    }

    /**
     * True when a player dealt damage and at least {@code minOfAverage} (0 to 1) of the average damage per player who dealt any. Relative to the
     * average, not to the pool: a fixed share of the pool would pay nobody once enough players split it evenly, and the event has no player cap.
     */
    public static boolean qualifies(float damage, float average, float minOfAverage) {
        return damage > 0f && damage >= average * minOfAverage;
    }

    /** The average damage per player who dealt any; 0 when nobody did. */
    public static float averageDamage(Map<UUID, Float> damage) {
        double total = 0;
        int dealers = 0;
        for (float value : damage.values()) {
            if (value > 0f && Float.isFinite(value)) {
                total += value;
                dealers++;
            }
        }
        return dealers == 0 ? 0f : (float) (total / dealers);
    }

    /** What a chained player's next step is. */
    public enum Step {
        /** Start the next duel now. */
        START,
        /** Keep waiting: the breather is not over. */
        WAIT,
        /** The pool is empty or the event is over: the chain ends for good. */
        FINISHED,
        /** The player cannot fight right now; the chain pauses until they ask to resume. */
        PAUSE_OFFLINE,
        PAUSE_OUT_OF_RANGE,
        PAUSE_BUSY,
        PAUSE_NO_POKEMON,
        PAUSE_OPTED_OUT
    }

    /**
     * Decides a chained player's next step. The order is deliberate: the end of the event beats
     * everything, and a player who has walked away or is busy is told so straight away rather than
     * after the wait. The breather comes before the Pokemon check (see below).
     */
    public static Step nextStep(boolean eventOver, boolean optedOut, boolean online, boolean inRange,
                                boolean busyInBattle, boolean hasFightingPokemon, boolean breatherElapsed) {
        if (eventOver) return Step.FINISHED;
        if (optedOut) return Step.PAUSE_OPTED_OUT;
        if (!online) return Step.PAUSE_OFFLINE;
        if (!inRange) return Step.PAUSE_OUT_OF_RANGE;
        if (busyInBattle) return Step.PAUSE_BUSY;
        // Before the Pokemon check on purpose: a fully fainted party is given its longer breather
        // to heal or revive, and is only told it cannot fight once that time has run out.
        if (!breatherElapsed) return Step.WAIT;
        if (!hasFightingPokemon) return Step.PAUSE_NO_POKEMON;
        return Step.START;
    }

    /**
     * Whether the next World Boss should start by itself: the system is on, the window is open
     * (a window-less config only ever starts by command), {@code spawn.auto_start} is on (off means only a command or another mod's
     * {@code CobbleRaidsWorldBoss.start} call starts one), nothing is active, and the gap since the
     * last one has passed.
     */
    public static boolean shouldAutoStart(WorldBossConfig config, Window window, boolean bossActive,
                                          long lastEndedMillis, long nowMillis) {
        if (!config.enabled() || !config.autoStart() || bossActive || window != Window.OPEN) return false;
        return nowMillis - lastEndedMillis >= config.spawnIntervalMinutes() * 60_000L;
    }

    /**
     * Whether a day time (game ticks, any size) falls in the night that runs from {@code from} to {@code to} in a 24000-tick day. The range may
     * wrap past midnight (22000 to 2000 is a night too); equal ends are rejected by the config, so they cannot mean "always" or "never" here.
     * Vanilla's own night is 13000 to 23000.
     */
    public static boolean isNight(long dayTime, int from, int to) {
        int time = (int) Math.floorMod(dayTime, 24000L);
        return from < to ? time >= from && time < to : time >= from || time < to;
    }

    /** The zone radius in force: the arena's own when the boss stands at an arena marker, otherwise the open-world radius. */
    public static double zoneRadius(WorldBossState state, WorldBossConfig config) {
        return state != null && state.arena() ? config.arena().radius() : config.radius();
    }

    /**
     * When a World Boss that started now should leave if undefeated: its lifetime, but never past
     * the end of the event window, and never sooner than a minute.
     */
    public static long endMillis(WorldBossConfig config, long startMillis) {
        long end = startMillis + config.spawnLifetimeMinutes() * 60_000L;
        if (config.windowEnd().isPresent()) end = Math.min(end, config.windowEnd().get().toEpochMilli());
        return Math.max(end, startMillis + 60_000L);
    }

    /** Rounds a coordinate to the nearest hundred, so an announcement points near the boss and not at it. */
    public static int coordinateHint(int coordinate) {
        return coordinate >= 0 ? (coordinate + 50) / 100 * 100 : (coordinate - 50) / 100 * 100;
    }

    /**
     * Picks the next World Boss from the configured options, avoiding a repeat of the previous one
     * whenever there is another choice.
     *
     * @param options  the candidates, already limited to definitions that are loaded; empty gives null
     * @param previous the last boss's id, or null/empty for none
     */
    public static String pickDefinition(List<String> options, String previous, Random random) {
        if (options.isEmpty()) return null;
        List<String> fresh = new ArrayList<>(options);
        if (fresh.size() > 1 && previous != null) fresh.remove(previous);
        return fresh.get(random.nextInt(fresh.size()));
    }
}
