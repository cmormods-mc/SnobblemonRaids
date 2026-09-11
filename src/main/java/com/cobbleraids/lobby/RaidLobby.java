package com.cobbleraids.lobby;

import com.cobbleraids.config.RaidDefinition;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Set;
import java.util.UUID;

/**
 * Ephemeral pre-battle recruitment state. No RaidSession exists until this lobby freezes.
 *
 * <p>Identity lives here -- which boss, which definition -- and the roster rules live in
 * {@link RaidRecruitmentRoster}, which this delegates to so they can be tested without a server.
 */
public final class RaidLobby {
    public enum Status { RECRUITING, STARTING, STARTED, CANCELLED }

    private final UUID id = UUID.randomUUID();
    private final PokemonEntity boss;
    private final RaidDefinition definition;
    private final RaidRecruitmentRoster roster;

    public RaidLobby(PokemonEntity boss, RaidDefinition definition, long openedAtTick) {
        this.boss = boss;
        this.definition = definition;
        this.roster = new RaidRecruitmentRoster(
                definition.recruitment().maxPlayers(),
                openedAtTick,
                definition.recruitment().durationSeconds());
    }

    public UUID id() { return id; }
    public PokemonEntity boss() { return boss; }
    public RaidDefinition definition() { return definition; }

    /** The testable half, for code that wants the roster rules rather than the boss. */
    public RaidRecruitmentRoster roster() { return roster; }

    public long openedAtTick() { return roster.openedAtTick(); }
    public long closesAtTick() { return roster.closesAtTick(); }
    public Set<UUID> optedIn() { return roster.optedIn(); }
    public boolean isOptedIn(UUID playerId) { return roster.isOptedIn(playerId); }
    public boolean join(UUID playerId) { return roster.join(playerId); }
    public int joinedCount() { return roster.joinedCount(); }
    public void starting() { roster.starting(); }
    public void started() { roster.started(); }
    public void cancel() { roster.cancel(); }

    public Status status() {
        return switch (roster.status()) {
            case RECRUITING -> Status.RECRUITING;
            case STARTING -> Status.STARTING;
            case STARTED -> Status.STARTED;
            case CANCELLED -> Status.CANCELLED;
        };
    }
}
