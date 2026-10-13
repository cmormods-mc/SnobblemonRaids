package com.cobbleraids.web;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Delivers {@link WebIcons} to one kind of page, once per open of that page.
 *
 * <p>WebGUI caps a single event at 32 KB, so icons never ride inside a page's own message. The page
 * caches them by key; this remembers which keys each player's open page already holds and sends only
 * the rest, in batches that stay well under the cap.
 */
final class WebIconFeed {
    /** Comfortably under WebGUI's 32 KB event limit once JSON escaping is counted. */
    private static final int BATCH_CHARS = 24_000;
    private static final List<WebIconFeed> ALL = new ArrayList<>();

    private final String event;
    private final Map<UUID, Set<String>> sent = new ConcurrentHashMap<>();

    WebIconFeed(String event) {
        this.event = event;
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** A page has just opened and holds nothing yet. */
    void reset(UUID player) {
        sent.put(player, new HashSet<>());
    }

    /** Forgets a disconnected player on every feed. */
    static void forgetEverywhere(UUID player) {
        synchronized (ALL) {
            for (WebIconFeed feed : ALL) feed.sent.remove(player);
        }
    }

    /** Sends whichever of these keys the player's open page does not hold yet. */
    void send(ServerPlayer player, Collection<String> keys) {
        Set<String> held = sent.computeIfAbsent(player.getUUID(), id -> new HashSet<>());
        JsonObject batch = new JsonObject();
        int size = 0;
        for (String key : keys) {
            if (held.contains(key)) continue;
            String url = WebIcons.dataUrl(key).orElse(null);
            if (url == null) continue;
            if (size > 0 && size + url.length() > BATCH_CHARS) {
                WebGuiBridge.emit(player, event, batch.toString());
                batch = new JsonObject();
                size = 0;
            }
            batch.addProperty(key, url);
            size += url.length() + key.length();
            held.add(key);
        }
        if (size > 0) WebGuiBridge.emit(player, event, batch.toString());
    }
}
