package com.cobbleraids.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raid recruitment: who gets in, how many, for how long, and what a lobby will still accept once it
 * has begun freezing.
 *
 * <p>None of this was tested, and the package has four fix commits behind it. Recruitment is also
 * where a four-player bug cost several sessions of live debugging, so the multi-player boundaries
 * are pinned here deliberately rather than left to a bot that may or may not find the boss.
 */
class RaidRecruitmentRosterTest {

    private static final int MAX_PLAYERS = 4;
    private static final int DURATION_SECONDS = 60;

    private RaidRecruitmentRoster roster() {
        return new RaidRecruitmentRoster(MAX_PLAYERS, 0L, DURATION_SECONDS);
    }

    @Test
    @DisplayName("a player joins once and only once")
    void joinIsIdempotent() {
        RaidRecruitmentRoster roster = roster();
        UUID player = UUID.randomUUID();

        assertTrue(roster.join(player));
        assertFalse(roster.join(player), "a second right-click must not count as a second player");
        assertEquals(1, roster.joinedCount());
        assertTrue(roster.isOptedIn(player));
    }

    @Test
    @DisplayName("the roster fills to max_players and then refuses")
    void capacityIsEnforced() {
        RaidRecruitmentRoster roster = roster();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            assertTrue(roster.join(UUID.randomUUID()), "player " + (i + 1) + " should fit");
        }

        assertTrue(roster.isFull());
        assertFalse(roster.join(UUID.randomUUID()), "the fifth player must be turned away");
        assertEquals(MAX_PLAYERS, roster.joinedCount());
    }

    @Test
    @DisplayName("join order is preserved, because Showdown actor numbering is derived from it")
    void orderIsPreserved() {
        // Not cosmetic. The roster becomes side-1's actor order at freeze, and actors are renumbered
        // p1..pN from it; a reshuffle between lobby and battle would put the Java and Showdown sides
        // out of agreement about which player is which.
        RaidRecruitmentRoster roster = roster();
        List<UUID> joined = new ArrayList<>();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            UUID player = UUID.randomUUID();
            joined.add(player);
            roster.join(player);
        }

        assertEquals(joined, List.copyOf(roster.optedIn()));
    }

    @Test
    @DisplayName("recruitment closes on its exact tick")
    void windowClosesOnTheTick() {
        RaidRecruitmentRoster roster = new RaidRecruitmentRoster(MAX_PLAYERS, 100L, 30);

        assertEquals(100L + 30 * 20L, roster.closesAtTick());
        assertFalse(roster.hasClosed(roster.closesAtTick() - 1));
        assertTrue(roster.hasClosed(roster.closesAtTick()));
    }

    @Test
    @DisplayName("nobody joins a lobby that is already freezing")
    void noJoiningOnceStarting() {
        RaidRecruitmentRoster roster = roster();
        roster.join(UUID.randomUUID());
        roster.starting();

        assertFalse(roster.join(UUID.randomUUID()),
                "a join racing the freeze would add an actor the battle was not built with");
        assertEquals(1, roster.joinedCount());
        assertFalse(roster.isRecruiting());
    }

    @Test
    @DisplayName("status advances only along RECRUITING to STARTING to STARTED")
    void statusIsOneWay() {
        RaidRecruitmentRoster roster = roster();
        assertEquals(RaidRecruitmentRoster.Status.RECRUITING, roster.status());

        roster.started();
        assertEquals(RaidRecruitmentRoster.Status.RECRUITING, roster.status(),
                "STARTED must not be reachable without passing through STARTING");

        roster.starting();
        assertEquals(RaidRecruitmentRoster.Status.STARTING, roster.status());
        roster.starting();
        assertEquals(RaidRecruitmentRoster.Status.STARTING, roster.status(), "no-op when repeated");

        roster.started();
        assertEquals(RaidRecruitmentRoster.Status.STARTED, roster.status());
    }

    @Test
    @DisplayName("a started lobby can no longer be cancelled")
    void startedCannotBeCancelled() {
        RaidRecruitmentRoster roster = roster();
        roster.join(UUID.randomUUID());
        roster.starting();
        roster.started();

        roster.cancel();

        assertEquals(RaidRecruitmentRoster.Status.STARTED, roster.status(),
                "the RaidSession owns the outcome by now; a late cancel would strand a live battle");
    }

    @Test
    @DisplayName("a recruiting or freezing lobby can be cancelled")
    void cancellableBeforeStart() {
        RaidRecruitmentRoster recruiting = roster();
        recruiting.cancel();
        assertEquals(RaidRecruitmentRoster.Status.CANCELLED, recruiting.status());

        RaidRecruitmentRoster freezing = roster();
        freezing.starting();
        freezing.cancel();
        assertEquals(RaidRecruitmentRoster.Status.CANCELLED, freezing.status(),
                "a freeze that fails mid-way has to be able to release the boss");
    }

    @Test
    @DisplayName("a cancelled lobby accepts nobody")
    void cancelledAcceptsNobody() {
        RaidRecruitmentRoster roster = roster();
        roster.cancel();

        assertFalse(roster.join(UUID.randomUUID()));
        assertEquals(0, roster.joinedCount());
    }

    @Test
    @DisplayName("a max_players of 1 still admits its solo raider")
    void soloRaidIsPossible() {
        RaidRecruitmentRoster roster = new RaidRecruitmentRoster(1, 0L, DURATION_SECONDS);

        assertTrue(roster.join(UUID.randomUUID()));
        assertTrue(roster.isFull());
        assertFalse(roster.join(UUID.randomUUID()));
    }

    @Test
    @DisplayName("the opted-in view is a detached copy")
    void optedInIsDetached() {
        RaidRecruitmentRoster roster = roster();
        UUID first = UUID.randomUUID();
        roster.join(first);

        var view = roster.optedIn();
        assertThrows(UnsupportedOperationException.class, () -> view.add(UUID.randomUUID()));

        roster.join(UUID.randomUUID());
        assertEquals(1, view.size(), "an earlier view must not see later joins");
    }
}
