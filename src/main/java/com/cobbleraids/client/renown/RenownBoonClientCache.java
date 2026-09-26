package com.cobbleraids.client.renown;

import com.cobbleraids.RaidLog;
import com.cobbleraids.renown.RenownBoon;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What {@link com.cobbleraids.network.RenownBoonSyncPayload} fills in: a renowned boss's boon,
 * keyed by its Cobblemon Pokemon UUID (not the Minecraft entity UUID).
 *
 * <p>This is the only channel a renowned boss's boon actually reaches the client through. An
 * earlier version tried to smuggle it in the boss's own display name (a {@code Style.insertion}
 * riding on the epithet), which cannot work: {@code PokemonEntity.setCustomName} flattens whatever
 * {@code Component} it is given down to a bare string before storing it, discarding every
 * {@code Style} attribute. See {@link com.cobbleraids.presentation.RenownBoonSyncService} for where
 * this map is populated (on spawn, and resent periodically so a late-tracking client still gets it).
 */
public final class RenownBoonClientCache {
    private static final Map<UUID, RenownBoon> CACHE = new ConcurrentHashMap<>();
    // TEMPORARY diagnostic while the boon-icon feature is being live-tested -- remove once confirmed
    // working. Logs a miss once per uuid rather than every frame a mixin queries it.
    private static final Set<UUID> LOGGED_MISSES = ConcurrentHashMap.newKeySet();

    private RenownBoonClientCache() {}

    public static void remember(UUID pokemonUuid, RenownBoon boon) {
        if (pokemonUuid == null || boon == null) return;
        CACHE.put(pokemonUuid, boon);
        LOGGED_MISSES.remove(pokemonUuid);
    }

    public static Optional<RenownBoon> forPokemonUuid(UUID pokemonUuid) {
        if (pokemonUuid == null) return Optional.empty();
        RenownBoon boon = CACHE.get(pokemonUuid);
        if (boon == null && LOGGED_MISSES.add(pokemonUuid)) {
            RaidLog.info("[boon-sync] cache miss for pokemonUuid={} ({} entries cached: {})",
                    pokemonUuid, CACHE.size(), CACHE.keySet());
        }
        return Optional.ofNullable(boon);
    }
}
