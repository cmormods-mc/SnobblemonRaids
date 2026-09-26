package com.cobbleraids.presentation;

import com.cobbleraids.RaidLog;
import com.cobbleraids.network.RenownBoonSyncPayload;
import com.cobbleraids.renown.RaidRenownMarker;
import com.cobbleraids.renown.RenownBoon;
import com.cobbleraids.spawn.RaidBossLookup;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Tells clients which boon a renowned raid boss has, over a dedicated packet rather than trying to
 * ride along on the boss's display name.
 *
 * <p>{@code PokemonEntity.setCustomName} flattens whatever {@code Component} it is given down to a
 * bare string before storing it, discarding every {@code Style} attribute -- which is exactly where
 * an earlier version of this feature tried to hide the boon marker. See
 * {@link com.cobbleraids.renown.RenownBoon} and {@link RaidBossNameplate} for what still rides on
 * the (flattened) text itself; this service exists for the one piece of data that cannot.
 *
 * <p>Shaped like {@link RaidBossGlowService}: register at spawn, prune on genuine destruction, tick
 * to catch what registration alone cannot. There is no public "a player started tracking this
 * entity" event in this Fabric API version, so instead of trying to catch that moment exactly, this
 * just resends the tiny payload to every current tracker once a second for as long as the boss
 * lives -- self-healing against a late join, a reconnect, or a player walking into range, at the
 * cost of one UUID and one short string per tracked renowned boss per second.
 */
public final class RenownBoonSyncService {
    private static final Map<UUID, ResourceLocation> TRACKED_DIMENSION = new ConcurrentHashMap<>();
    private static final Map<UUID, RenownBoon> TRACKED_BOON = new ConcurrentHashMap<>();
    private static long tickCounter;

    private RenownBoonSyncService() {}

    /** Called once, right after a renowned boss is created. Does nothing for an ordinary boss. */
    public static void register(PokemonEntity boss, ServerLevel level, RenownBoon boon) {
        if (boon == null) return;
        TRACKED_DIMENSION.put(boss.getUUID(), level.dimension().location());
        TRACKED_BOON.put(boss.getUUID(), boon);
        sendTo(PlayerLookup.tracking(boss), boss, boon);
    }

    public static void onServerStopping(MinecraftServer server) {
        TRACKED_DIMENSION.clear();
        TRACKED_BOON.clear();
        tickCounter = 0L;
    }

    /**
     * Re-registers a renowned boss that loads without ever having called {@link #register}.
     *
     * <p>Same gap as {@link RaidBossGlowService#onEntityLoaded}, for the same reason: register() only
     * runs at spawn, so a renowned boss whose raid survived a restart -- clean shutdown or a crash,
     * either one -- comes back with no TRACKED_BOON entry, and its boon silently stops syncing to any
     * client until the boss is destroyed and respawned. The boon survives on the entity regardless,
     * as scoreboard tags (see {@link RaidRenownMarker}), so it is recovered from there rather than
     * lost. Bound to ServerEntityEvents.ENTITY_LOAD rather than a boot-time sweep for the same reason
     * as the glow sibling: entity sections load asynchronously after the server reports ready.
     *
     * <p>Guarded by {@link RaidBossSpawner#isSpawning()} for the same reason as the glow sibling too:
     * RaidBossSpawner.spawnAt() tags a freshly spawned renowned boss (RaidRenownMarker.mark) from
     * inside the same sendOut() call that triggers ENTITY_LOAD, before it gets the entity back to call
     * this service's own register() -- so without the guard, every renowned spawn would log a spurious
     * recovery here.
     */
    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        if (RaidBossSpawner.isSpawning()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (TRACKED_BOON.containsKey(pokemon.getUUID())) return;
        RaidRenownMarker.read(pokemon).ifPresent(renown -> {
            RaidLog.info("Re-registered a renowned raid boss with the boon-sync service after it "
                            + "(re)loaded untracked ({} in {})",
                    pokemon.getUUID(), level.dimension().location());
            register(pokemon, level, renown.boon());
        });
    }

    public static void onEntityUnloaded(Entity entity, ServerLevel level) {
        if (TRACKED_BOON.isEmpty()) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;
        if (!(entity instanceof PokemonEntity pokemon)) return;
        TRACKED_DIMENSION.remove(pokemon.getUUID());
        TRACKED_BOON.remove(pokemon.getUUID());
    }

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if ((tickCounter % 20L) != 0L) return;
        if (TRACKED_BOON.isEmpty()) return;

        Iterator<Map.Entry<UUID, ResourceLocation>> iterator = TRACKED_DIMENSION.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ResourceLocation> entry = iterator.next();
            RenownBoon boon = TRACKED_BOON.get(entry.getKey());
            if (boon == null) {
                iterator.remove();
                continue;
            }
            PokemonEntity boss = RaidBossLookup.resolve(server, entry.getKey(), entry.getValue());
            // Not resolved means the chunk is unloaded, not that the boss is gone -- see
            // RaidBossLookup's own contract note. Keep waiting for it rather than dropping it.
            if (boss == null) continue;
            sendTo(PlayerLookup.tracking(boss), boss, boon);
        }
    }

    private static void sendTo(Iterable<ServerPlayer> players, PokemonEntity boss, RenownBoon boon) {
        RenownBoonSyncPayload payload = new RenownBoonSyncPayload(boss.getPokemon().getUuid(), boon.encode());
        int sent = 0;
        int skippedCannotSend = 0;
        for (ServerPlayer player : players) {
            if (ServerPlayNetworking.canSend(player, RenownBoonSyncPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
                sent++;
            } else {
                skippedCannotSend++;
            }
        }
        // TEMPORARY diagnostic while the boon-icon feature is being live-tested -- remove once
        // confirmed working. Once a second per renowned boss is not worth silencing further.
        RaidLog.info("[boon-sync] sent pokemonUuid={} boon={} to {} tracker(s), {} could not receive it",
                payload.pokemonUuid(), payload.boonEncoded(), sent, skippedCannotSend);
    }
}
