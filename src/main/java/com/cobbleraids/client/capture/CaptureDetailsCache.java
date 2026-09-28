package com.cobbleraids.client.capture;

import com.cobbleraids.network.CaptureDetailsPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the most recent {@link CaptureDetailsPayload} per raid until the matching offer screen reads
 * it. Populated and cleared from {@code CobbleRaidsClient} -- see that class's own javadoc for why
 * this doesn't register its own network or connection-lifecycle listeners.
 *
 * <p>Capped rather than unbounded: a capture offer the player never opens (disconnects, ignores it)
 * would otherwise leak one entry per raid for the rest of the session.
 */
public final class CaptureDetailsCache {
    private static final int MAX_ENTRIES = 32;
    private static final Map<UUID, CaptureDetailsPayload> VALUES = new LinkedHashMap<>();

    private CaptureDetailsCache() {}

    public static void remember(CaptureDetailsPayload payload) {
        if (VALUES.size() >= MAX_ENTRIES) {
            VALUES.remove(VALUES.keySet().iterator().next());
        }
        VALUES.put(payload.raidId(), payload);
    }

    /** Removes and returns the cached details for {@code raidId}, or null if none arrived. */
    public static CaptureDetailsPayload take(UUID raidId) {
        return VALUES.remove(raidId);
    }

    public static void clear() {
        VALUES.clear();
    }
}
