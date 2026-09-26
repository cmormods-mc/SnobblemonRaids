package com.cobbleraids.presentation;

import com.cobbleraids.RaidLog;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossLookup;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Shared shape for a service that tracks raid bosses from spawn to destruction and refreshes some
 * player-facing side effect once a second for as long as each one is alive.
 *
 * <p>Extracted after this exact rule -- register at spawn, recover a restart orphan via
 * ENTITY_LOAD, prune on genuine destruction via ENTITY_UNLOAD, and catch what neither event can --
 * had been written wrong in two different services in this codebase, in the same way both times:
 * register() only ever ran at spawn, so a boss whose raid survived a restart came back with no
 * entry, and the side effect silently stopped until the boss was destroyed and respawned. Fixed
 * once each time it was found ({@code RaidBossGlowService} in 00986f4, {@code RenownBoonSyncService}
 * in 7726f31) before this existed to fix it for both at once.
 *
 * <p>{@code P} is whatever per-boss data the side effect needs beyond the dimension, which every
 * tracked boss needs regardless -- {@code RenownBoonSyncService} tracks the boon;
 * {@code RaidBossGlowService} needs nothing extra and uses a constant marker.
 */
abstract class TrackedBossService<P> {
    private final Map<UUID, ResourceLocation> dimensions = new ConcurrentHashMap<>();
    private final Map<UUID, P> payloads = new ConcurrentHashMap<>();
    private final String label;
    private long tickCounter;

    /** @param label Names this service in the recovery log line, e.g. "glow service", "boon-sync service". */
    protected TrackedBossService(String label) {
        this.label = label;
    }

    /** Called once, right after a boss is created, regardless of how it was spawned. */
    protected final void register(PokemonEntity boss, ServerLevel level, P payload) {
        dimensions.put(boss.getUUID(), level.dimension().location());
        payloads.put(boss.getUUID(), payload);
        onRegistered(boss, payload);
    }

    protected final boolean isTracked(UUID bossId) {
        return payloads.containsKey(bossId);
    }

    /** Every boss this service is tracking, and the dimension it was registered in. For auditing. */
    protected final Map<UUID, ResourceLocation> trackedBosses() {
        return Map.copyOf(dimensions);
    }

    /**
     * Drops all cross-server state on shutdown. Tracked ids are only meaningful for the server that
     * produced them, and an integrated (single-player) client reuses this JVM for every world it
     * opens, so anything left here would be read back against the next world's entities. Every
     * currently-tracked boss also gets {@link #onUntracked}, resolved if possible, exactly as if it
     * had been found genuinely gone -- {@code RaidBossGlowService} needs this to take a boss back
     * out of its scoreboard team before the world closes.
     */
    protected final void onServerStopping(MinecraftServer server) {
        for (Map.Entry<UUID, P> entry : Map.copyOf(payloads).entrySet()) {
            UUID bossId = entry.getKey();
            ResourceLocation dimension = dimensions.get(bossId);
            PokemonEntity boss = dimension == null ? null : RaidBossLookup.resolve(server, bossId, dimension);
            onUntracked(server, bossId, boss, entry.getValue());
        }
        dimensions.clear();
        payloads.clear();
        tickCounter = 0L;
    }

    /**
     * Re-registers a boss that loads without ever having called {@link #register}.
     *
     * <p>register() only runs at spawn, so a boss whose raid was still going when the server last
     * stopped -- clean shutdown or a crash, either one -- comes back on restart with no tracked
     * entry, silently losing this service's side effect until the boss is destroyed and respawned.
     * Bound to ServerEntityEvents.ENTITY_LOAD rather than a boot-time sweep, because entity sections
     * load asynchronously after the server reports ready and a sweep run too early would miss this
     * exact case.
     *
     * <p>Guarded by {@code RaidBossSpawner.isSpawning()}: a freshly spawned boss is NOT naturally
     * excluded by marker timing -- RaidBossSpawner.spawnAt() tags the entity from inside the same
     * sendOut() call that triggers ENTITY_LOAD, before it gets the entity back to call this
     * service's own register() -- so without the guard, every ordinary spawn would log a spurious
     * recovery here.
     */
    public final void onEntityLoaded(Entity entity, ServerLevel level) {
        if (RaidBossSpawner.isSpawning()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (isTracked(pokemon.getUUID())) return;
        recoverPayload(pokemon).ifPresent(payload -> {
            RaidLog.info("Re-registered a raid boss with the {} after it (re)loaded untracked ({} in {})",
                    label, pokemon.getUUID(), level.dimension().location());
            register(pokemon, level, payload);
        });
    }

    /** Untracks a boss the moment it is genuinely destroyed, whatever destroyed it. */
    public final void onEntityUnloaded(Entity entity, ServerLevel level) {
        if (payloads.isEmpty()) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        // Ordered cheapest-first: this fires for every entity leaving every chunk, and an ordinary
        // unload is rejected by one field read. UNLOADED_TO_CHUNK is not a destruction, and a boss
        // that merely unloaded must stay tracked so its side effect resumes when its chunk returns.
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        UUID bossId = pokemon.getUUID();
        dimensions.remove(bossId);
        P payload = payloads.remove(bossId);
        if (payload != null) onUntracked(level.getServer(), bossId, pokemon, payload);
    }

    public final void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % 20L) != 0L) return;
        if (payloads.isEmpty()) return;

        Iterator<Map.Entry<UUID, ResourceLocation>> iterator = dimensions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ResourceLocation> entry = iterator.next();
            UUID bossId = entry.getKey();
            P payload = payloads.get(bossId);
            if (payload == null) {
                iterator.remove();
                continue;
            }

            PokemonEntity boss = RaidBossLookup.resolve(server, bossId, entry.getValue());
            // "Does not resolve" means the chunk holding it is not loaded, which is not the same as
            // gone. Dropping an unloaded boss here would be permanent, because register() only ever
            // runs at spawn: the boss would come back when its chunk reloaded and never be tracked
            // again. A boss that is really destroyed is untracked by onEntityUnloaded instead, except
            // for the one case that cannot fire an unload event at all -- an entity destroyed while
            // its section is inaccessible skips PersistentEntitySectionManager.stopTracking. This
            // fallback is what catches that.
            if (shouldUntrack(boss != null, boss != null && boss.isRemoved(),
                    boss != null && RaidBossEntityMarker.isRaidBoss(boss))) {
                iterator.remove();
                payloads.remove(bossId);
                onUntracked(server, bossId, boss, payload);
                continue;
            }
            if (boss == null) continue;

            refresh(server, boss, payload);
        }
    }

    /**
     * Whether a tracked boss should be dropped, given what the world could tell us about it.
     *
     * <p>A predicate rather than an inline condition because this exact rule had been written wrong
     * three times in this codebase before this class existed to hold it once. The trap is always the
     * same: a boss that does not resolve looks identical to a boss that no longer exists, and
     * treating the first as the second drops something that is still out there.
     *
     * @param resolved      the entity was found, i.e. its chunk and dimension are loaded
     * @param removed       it was found and reports itself removed
     * @param stillRaidBoss it was found and still carries the raid-boss marker
     */
    static boolean shouldUntrack(boolean resolved, boolean removed, boolean stillRaidBoss) {
        if (!resolved) return false;
        return removed || !stillRaidBoss;
    }

    /** Recovers this service's own payload from a boss that loaded untracked, or empty for a boss it does not own. */
    protected abstract Optional<P> recoverPayload(PokemonEntity pokemon);

    /** Called once a second for a boss that is still alive and still a raid boss. */
    protected abstract void refresh(MinecraftServer server, PokemonEntity boss, P payload);

    /**
     * Extra cleanup once a tracked boss is confirmed gone, from {@link #onEntityUnloaded}, the tick
     * fallback, or server shutdown. {@code boss} is null only from server shutdown, when the entity
     * could not be resolved either.
     */
    protected void onUntracked(MinecraftServer server, UUID bossId, PokemonEntity boss, P payload) {}

    /** Extra action to take right at registration, beyond storing the payload. Default: none. */
    protected void onRegistered(PokemonEntity boss, P payload) {}
}
