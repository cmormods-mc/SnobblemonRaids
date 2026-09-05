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
        if (battle != null) BY_BATTLE.remove(battle.getBattleId());
    }

    public static boolean contains(PokemonBattle battle) { return get(battle) != null; }
}
