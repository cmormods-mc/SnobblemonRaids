package com.cobbleraids.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player request budget: at most {@code maxPerWindow} requests in any {@code windowMillis},
 * the rest refused.
 *
 * <p>This is a flood guard, not pacing. The screens send one request at a time and wait for the
 * reply, and they never resend after a refusal, so a refused request would leave a legitimate screen
 * on "Loading..." for good. The budget is therefore set far above anything a person can do, and only
 * a client that is not playing by the screen's rules ever reaches it.
 *
 * <p>Not thread-safe by design: every caller runs on the server thread.
 */
public final class RequestThrottle {

    private final int maxPerWindow;
    private final long windowMillis;
    private final Map<UUID, Window> windows = new HashMap<>();

    public RequestThrottle(int maxPerWindow, long windowMillis) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
    }

    /** True if this request is within the player's budget, counting it; false if it should be dropped. */
    public boolean allow(UUID player, long nowMillis) {
        Window window = windows.get(player);
        // A clock that moved backwards starts a fresh window rather than locking the player out.
        if (window == null || nowMillis < window.start || nowMillis - window.start >= windowMillis) {
            windows.put(player, new Window(nowMillis, 1));
            return true;
        }
        if (window.count >= maxPerWindow) return false;
        window.count++;
        return true;
    }

    /** Drops a player's state when they disconnect, so the map cannot grow without bound. */
    public void forget(UUID player) {
        windows.remove(player);
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
