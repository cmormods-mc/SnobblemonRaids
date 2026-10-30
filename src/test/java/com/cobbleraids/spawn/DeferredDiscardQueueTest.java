package com.cobbleraids.spawn;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeferredDiscardQueueTest {
    @Test
    void waitsForActualDeadlineEvenIfTasksCouldRunImmediately() {
        var queue = new DeferredDiscardQueue<String>();
        var removed = new ArrayList<String>();
        queue.schedule("boss", 12, () -> true, () -> removed.add("boss"));
        queue.tick(10, 256);
        queue.tick(11, 256);
        assertTrue(removed.isEmpty());
        queue.tick(12, 256);
        queue.tick(13, 256);
        assertEquals(List.of("boss"), removed);
    }

    @Test
    void newlyOwnedOrBattlingBossIsSparedAndDoesNotStayQueued() {
        var queue = new DeferredDiscardQueue<String>();
        var orphaned = new AtomicBoolean(true);
        queue.schedule("boss", 2, orphaned::get, () -> fail("a rescued boss must survive"));
        orphaned.set(false);
        queue.tick(2, 256);
        orphaned.set(true);
        queue.tick(3, 256);
    }

    @Test
    void duplicateNotificationsKeepFirstDeadlineAndBoundWork() {
        var queue = new DeferredDiscardQueue<String>();
        var removed = new ArrayList<String>();
        queue.schedule("a", 2, () -> true, () -> removed.add("a"));
        queue.schedule("a", 5, () -> true, () -> fail("duplicate"));
        queue.schedule("b", 2, () -> true, () -> removed.add("b"));
        queue.tick(2, 1);
        assertEquals(List.of("a"), removed);
        queue.tick(3, 1);
        assertEquals(List.of("a", "b"), removed);
    }

    @Test
    void reentrantSchedulingWaitsAndShutdownDropsPendingReferences() {
        var queue = new DeferredDiscardQueue<String>();
        queue.schedule("a", 2, () -> true, () -> queue.schedule("b", 4, () -> true, () -> fail("cleared at stop")));
        queue.tick(2, 256);
        queue.clear();
        queue.tick(4, 256);
    }
}
