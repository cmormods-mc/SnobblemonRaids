package com.cobbleraids.client.renown;

import com.cobbleraids.renown.RenownBoon;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    /** Far more renowned bosses than can be in view at once; the server resends periodically anyway. */
    private static final int MAX_ENTRIES = 256;
    // Access-ordered so the boss being looked at stays and long-gone ones age out. Synchronized
    // because a render reads it every frame while the network handler writes it.
    private static final Map<UUID, RenownBoon> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, RenownBoon> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private RenownBoonClientCache() {}

    public static void remember(UUID pokemonUuid, RenownBoon boon) {
        if (pokemonUuid == null || boon == null) return;
        CACHE.put(pokemonUuid, boon);
    }

    /** Pokemon UUIDs are only meaningful for the world they came from; dropped on disconnect. */
    public static void clear() {
        CACHE.clear();
    }

    public static Optional<RenownBoon> forPokemonUuid(UUID pokemonUuid) {
        if (pokemonUuid == null) return Optional.empty();
        return Optional.ofNullable(CACHE.get(pokemonUuid));
    }
}
