package com.cobbleraids.raid;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe association between one Cobblemon battle and one raid session. */
public final class RaidRegistry {
    private static final Map<UUID, RaidSession> BY_BATTLE = new ConcurrentHashMap<>();
    private RaidRegistry() {}

    public static void bind(RaidSession session) {
        Objects.requireNonNull(session);
        BY_BATTLE.put(session.getBattle().getBattleId(), session);
    }

    public static RaidSession get(PokemonBattle battle) {
        return battle == null ? null : BY_BATTLE.get(battle.getBattleId());
    }

    public static Collection<RaidSession> all() {
        return List.copyOf(BY_BATTLE.values());
    }

    /** Cheap pre-check so a tick-driven caller can skip the defensive copy in all() when idle. */
    public static boolean isEmpty() {
        return BY_BATTLE.isEmpty();
    }

    public static void remove(PokemonBattle battle) {
        if (battle == null) return;
        RaidSession removed = BY_BATTLE.remove(battle.getBattleId());
        // The raid is over, however it ended: AscensionLib forgets its boss and any Scouter reveals (an owned raid's encounter is
        // its owner's to end).
        if (removed != null && !removed.isOwned()) AscensionLibArming.end(removed.getBossActorId());
    }

    public static boolean contains(PokemonBattle battle) { return get(battle) != null; }

    /**
     * Drops every session once the server is gone. A RaidSession holds its PokemonBattle and its
     * boss PokemonEntity, and an entity reaches its ServerLevel, so a session left here after a
     * world closes pins that entire world in memory. An integrated (single-player) client reuses
     * this JVM for every world it opens, so the leak is per world visited, and the stale battle ids
     * would also be consulted against the next world's battles.
     */
    public static int onServerStopped() {
        int dropped = BY_BATTLE.size();
        BY_BATTLE.clear();
        return dropped;
    }
}
