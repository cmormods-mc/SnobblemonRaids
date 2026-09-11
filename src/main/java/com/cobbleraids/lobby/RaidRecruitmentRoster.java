package com.cobbleraids.lobby;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who has opted into a raid that has not started yet, and how far along recruitment is.
 *
 * <p>Split out of {@link RaidLobby} for the same reason {@code RaidProgress} was split out of
 * RaidSession: the rules are pure, but they were reachable only through a PokemonEntity and a
 * RaidDefinition, so nothing exercised them except a live server and a player right-clicking a boss.
 *
 * <p>Insertion order is deliberate and load-bearing, not incidental. The roster becomes the battle's
 * side-1 actor order at freeze, and CobbleRaids renumbers Showdown actors from that order (players
 * p1..pN, boss p(N+1)) -- so a LinkedHashSet here is what keeps the Java and Showdown sides agreeing
 * about who is who. A HashSet would reshuffle players between the lobby and the battle.
 *
 * <p>Status is one-way apart from cancellation, and a started lobby can no longer be cancelled: by
 * then the RaidSession owns the outcome, and letting a late cancel through would strand a running
 * battle with no lobby behind it.
 */
public final class RaidRecruitmentRoster {
    public enum Status { RECRUITING, STARTING, STARTED, CANCELLED }

    private final LinkedHashSet<UUID> optedIn = new LinkedHashSet<>();
    private final int maxPlayers;
    private final long openedAtTick;
    private final long closesAtTick;

    private Status status = Status.RECRUITING;

    public RaidRecruitmentRoster(int maxPlayers, long openedAtTick, int durationSeconds) {
        this.maxPlayers = maxPlayers;
        this.openedAtTick = openedAtTick;
        this.closesAtTick = openedAtTick + durationSeconds * 20L;
    }

    public long openedAtTick() { return openedAtTick; }
    public long closesAtTick() { return closesAtTick; }
    public int maxPlayers() { return maxPlayers; }

    public synchronized Status status() { return status; }
    public synchronized boolean isRecruiting() { return status == Status.RECRUITING; }
    public synchronized Set<UUID> optedIn() { return Collections.unmodifiableSet(new LinkedHashSet<>(optedIn)); }
    public synchronized boolean isOptedIn(UUID playerId) { return optedIn.contains(playerId); }
    public synchronized int joinedCount() { return optedIn.size(); }
    public synchronized boolean isFull() { return optedIn.size() >= maxPlayers; }

    /** True only if this call is what added the player: not while closed, full, or already joined. */
    public synchronized boolean join(UUID playerId) {
        if (status != Status.RECRUITING || optedIn.contains(playerId)) return false;
        if (optedIn.size() >= maxPlayers) return false;
        return optedIn.add(playerId);
    }

    /** Whether the recruitment window has run out at {@code nowTick}. */
    public boolean hasClosed(long nowTick) { return nowTick >= closesAtTick; }

    public synchronized void starting() { if (status == Status.RECRUITING) status = Status.STARTING; }
    public synchronized void started() { if (status == Status.STARTING) status = Status.STARTED; }
    public synchronized void cancel() { if (status != Status.STARTED) status = Status.CANCELLED; }
}
