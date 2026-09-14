package com.cobbleraids.raid;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe association between battle ids, raid ids, and active raid sessions. */
public final class RaidRegistry {
    private static final Map<UUID, RaidSession> BY_BATTLE = new ConcurrentHashMap<>();
    private static final Map<UUID, RaidSession> BY_RAID = new ConcurrentHashMap<>();

    private RaidRegistry() {}

    public static void bind(RaidSession session) {
        Objects.requireNonNull(session, "session");
        RaidSession previousBattle = BY_BATTLE.putIfAbsent(session.getBattle().getBattleId(), session);
        if (previousBattle != null) throw new IllegalStateException("Battle already has a raid session");

        RaidSession previousRaid = BY_RAID.putIfAbsent(session.getId(), session);
        if (previousRaid != null) {
            BY_BATTLE.remove(session.getBattle().getBattleId(), session);
            throw new IllegalStateException("Raid id already registered: " + session.getId());
        }
    }

    public static RaidSession get(PokemonBattle battle) {
        return battle == null ? null : BY_BATTLE.get(battle.getBattleId());
    }

    public static RaidSession get(UUID raidId) {
        return raidId == null ? null : BY_RAID.get(raidId);
    }

    public static Collection<RaidSession> all() {
        return List.copyOf(BY_BATTLE.values());
    }

    public static boolean isEmpty() {
        return BY_BATTLE.isEmpty();
    }

    public static void remove(PokemonBattle battle) {
        if (battle == null) return;
        RaidSession removed = BY_BATTLE.remove(battle.getBattleId());
        if (removed != null) BY_RAID.remove(removed.getId(), removed);
    }

    public static boolean contains(PokemonBattle battle) {
        return get(battle) != null;
    }

    public static int onServerStopped() {
        int dropped = BY_BATTLE.size();
        BY_BATTLE.clear();
        BY_RAID.clear();
        return dropped;
    }
}
