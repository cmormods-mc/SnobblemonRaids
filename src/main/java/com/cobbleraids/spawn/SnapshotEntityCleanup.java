package com.cobbleraids.spawn;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Finish traversing Minecraft's live entity lookup before removal callbacks can mutate it. */
final class SnapshotEntityCleanup {
    private SnapshotEntityCleanup() {}

    static <T> int removeMatching(Iterable<T> entities, Predicate<T> eligible, Consumer<T> remove) {
        var snapshot = new ArrayList<T>();
        for (T entity : entities) if (eligible.test(entity)) snapshot.add(entity);
        for (T entity : snapshot) remove.accept(entity);
        return snapshot.size();
    }
}
