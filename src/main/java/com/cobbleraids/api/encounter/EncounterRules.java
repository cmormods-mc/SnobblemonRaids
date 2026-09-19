package com.cobbleraids.api.encounter;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extra rules an owned encounter's battle is fought under.
 *
 * <p>A sibling to {@link EncounterPolicy}: that one says which of a raid's <i>side effects</i> the
 * owner wants, this one says how the <i>battle</i> differs from an ordinary one. Both travel on
 * {@link EncounterRequest}, so an owner states everything about an encounter in one place.
 *
 * <p><b>The neutral value is an ordinary battle.</b> {@link #none()} bans nothing, allows switching
 * and items, and sets no field condition -- so a caller that does not care about any of this cannot
 * accidentally restrict a fight by leaving a field unset. That is the opposite default from
 * {@code EncounterPolicy}, and deliberately: there, everything off means "no side effects", which is
 * also the harmless value.
 *
 * <p><b>These restrict the players, never the boss.</b> CobbleRaids' own safety ban list is applied
 * on top of {@code bannedMoves} and cannot be switched off by an owner -- those moves faint a
 * Pokemon outside the damage pipeline the raid's shared health pool is driven by, so allowing one
 * would not make a harder fight, it would make a stuck one.
 *
 * @param bannedMoves      Showdown move ids the players may not select, e.g. {@code "protect"}
 * @param switchingAllowed whether a player may switch Pokemon
 * @param itemsAllowed     whether a player may use bag or healing items
 * @param weather          a Showdown weather id to set when the battle starts, e.g. {@code
 *                         "raindance"}; empty leaves the field alone
 * @param terrain          a Showdown terrain id to set when the battle starts, e.g. {@code
 *                         "electricterrain"}
 * @param healthPercent    the boss's shared pool as a percentage of what it would otherwise be.
 *                         100 leaves it alone. This exists because the pool is derived <i>here</i>,
 *                         from the definition, the party size and the level -- an owner who wants a
 *                         tougher boss cannot compute that number, so it asks for a proportion
 *                         instead of a total
 */
public record EncounterRules(
        List<String> bannedMoves,
        boolean switchingAllowed,
        boolean itemsAllowed,
        Optional<String> weather,
        Optional<String> terrain,
        int healthPercent) {

    /**
     * What an id may look like.
     *
     * <p>Not decoration. A weather id reaches Showdown by being written into the {@code >start}
     * payload's format JSON, so an id carrying a quote or a brace would corrupt the payload for the
     * whole battle rather than merely failing to apply. Showdown's own ids are lowercase
     * alphanumerics, so anything else is a content mistake and is refused here, at the boundary,
     * where the message can name the field.
     */
    private static final Pattern ID = Pattern.compile("[a-z0-9]{1,32}");

    private static final EncounterRules NONE =
            new EncounterRules(List.of(), true, true, Optional.empty(), Optional.empty(), 100);

    /** The most a pool may be scaled, so a typo cannot make a boss that never dies. */
    public static final int MAX_HEALTH_PERCENT = 1000;

    /** An ordinary battle: nothing banned, switching and items allowed, no field condition. */
    public static EncounterRules none() {
        return NONE;
    }

    public EncounterRules {
        Objects.requireNonNull(bannedMoves, "bannedMoves");
        Objects.requireNonNull(weather, "weather");
        Objects.requireNonNull(terrain, "terrain");
        bannedMoves = bannedMoves.stream()
                .map(move -> move == null ? "" : move.trim().toLowerCase(Locale.ROOT))
                .filter(move -> !move.isEmpty())
                .distinct()
                .toList();
        for (String move : bannedMoves) requireId(move, "banned move");
        weather.ifPresent(id -> requireId(id, "weather"));
        terrain.ifPresent(id -> requireId(id, "terrain"));
        if (healthPercent < 1 || healthPercent > MAX_HEALTH_PERCENT) {
            throw new IllegalArgumentException("healthPercent must be 1.." + MAX_HEALTH_PERCENT
                    + ", got " + healthPercent);
        }
    }

    /** The shared pool this encounter should have, given what it would otherwise have been. */
    public long applyHealth(long derived) {
        return Math.max(1L, derived * healthPercent / 100);
    }

    private static void requireId(String value, String what) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value
                    + "' is not a Showdown id (lowercase letters and digits, 1-32 characters)");
        }
    }

    /** Whether any of this differs from an ordinary battle. */
    public boolean restrictsAnything() {
        return !bannedMoves.isEmpty() || !switchingAllowed || !itemsAllowed
                || weather.isPresent() || terrain.isPresent() || healthPercent != 100;
    }

    /** Whether a player may select {@code showdownMoveId}, ignoring CobbleRaids' own safety bans. */
    public boolean bans(String showdownMoveId) {
        return showdownMoveId != null && bannedMoves.contains(showdownMoveId.toLowerCase(Locale.ROOT));
    }

    public EncounterRules withBannedMoves(List<String> moves) {
        return new EncounterRules(moves, switchingAllowed, itemsAllowed, weather, terrain, healthPercent);
    }

    public EncounterRules withSwitching(boolean allowed) {
        return new EncounterRules(bannedMoves, allowed, itemsAllowed, weather, terrain, healthPercent);
    }

    public EncounterRules withItems(boolean allowed) {
        return new EncounterRules(bannedMoves, switchingAllowed, allowed, weather, terrain, healthPercent);
    }

    public EncounterRules withWeather(Optional<String> id) {
        return new EncounterRules(bannedMoves, switchingAllowed, itemsAllowed, id, terrain, healthPercent);
    }

    public EncounterRules withTerrain(Optional<String> id) {
        return new EncounterRules(bannedMoves, switchingAllowed, itemsAllowed, weather, id, healthPercent);
    }

    public EncounterRules withHealthPercent(int percent) {
        return new EncounterRules(bannedMoves, switchingAllowed, itemsAllowed, weather, terrain, percent);
    }
}
