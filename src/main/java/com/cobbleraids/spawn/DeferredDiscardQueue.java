package com.cobbleraids.spawn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Server-thread work with a real tick deadline and a condition checked again at execution. */
final class DeferredDiscardQueue<K> {
    private record Pending(long dueTick, BooleanSupplier eligible, Runnable discard) {}
    private final Map<K, Pending> pending = new LinkedHashMap<>();

    void schedule(K key, long dueTick, BooleanSupplier eligible, Runnable discard) {
        pending.putIfAbsent(key, new Pending(dueTick, eligible, discard));
    }

    void tick(long now, int budget) {
        if (budget <= 0) return;
        int processed = 0;
        for (var entry : List.copyOf(pending.entrySet())) {
            Pending task = entry.getValue();
            if (task.dueTick() > now || !pending.remove(entry.getKey(), task)) continue;
            if (task.eligible().getAsBoolean()) task.discard().run();
            if (++processed >= budget) break;
        }
    }

    void clear() { pending.clear(); }
}
