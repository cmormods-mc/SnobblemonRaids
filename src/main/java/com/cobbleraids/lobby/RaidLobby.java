package com.cobbleraids.lobby;

import com.cobbleraids.config.RaidDefinition;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Ephemeral pre-battle recruitment state. No RaidSession exists until this lobby freezes. */
public final class RaidLobby {
    public enum Status { RECRUITING, STARTING, STARTED, CANCELLED }

    private final UUID id = UUID.randomUUID();
    private final PokemonEntity boss;
    private final RaidDefinition definition;
    private final long openedAtTick;
    private final long closesAtTick;
    private final LinkedHashSet<UUID> optedIn = new LinkedHashSet<>();
    private Status status = Status.RECRUITING;

    public RaidLobby(PokemonEntity boss, RaidDefinition definition, long openedAtTick) {
        this.boss = boss;
        this.definition = definition;
        this.openedAtTick = openedAtTick;
        this.closesAtTick = openedAtTick + definition.recruitment().durationSeconds() * 20L;
    }

    public UUID id() { return id; }
    public PokemonEntity boss() { return boss; }
    public RaidDefinition definition() { return definition; }
    public long openedAtTick() { return openedAtTick; }
    public long closesAtTick() { return closesAtTick; }
    public synchronized Status status() { return status; }
    public synchronized Set<UUID> optedIn() { return Collections.unmodifiableSet(new LinkedHashSet<>(optedIn)); }

    public synchronized boolean join(UUID playerId) {
        if (status != Status.RECRUITING || optedIn.contains(playerId)) return false;
        if (optedIn.size() >= definition.recruitment().maxPlayers()) return false;
        return optedIn.add(playerId);
    }

    public synchronized int joinedCount() { return optedIn.size(); }
    public synchronized void starting() { if (status == Status.RECRUITING) status = Status.STARTING; }
    public synchronized void started() { if (status == Status.STARTING) status = Status.STARTED; }
    public synchronized void cancel() { if (status != Status.STARTED) status = Status.CANCELLED; }
}
