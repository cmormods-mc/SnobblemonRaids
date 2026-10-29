package com.cobbleraids.spawn;

import net.minecraft.server.MinecraftServer;
import com.cobbleraids.fault.RaidFaultBarrier;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Discards an entity a couple of ticks from now instead of inside the event that found it.
 *
 * <p>The leftover-boss cleanups run in {@code ENTITY_LOAD}, which fires while the entity is still being added to its chunk. A player who is
 * joining, or walking into range, gets the add packet when tracking starts; discarding the entity in the same tick can put the remove packet
 * ahead of it, and the client then keeps an entity the server no longer has: a model that stays in the world and cannot be touched until the
 * player reconnects. Waiting two ticks lets the add go out first, so the remove always follows it.
 */
public final class RaidDeferredDiscard {
    private static final int DELAY_TICKS = 2;
    private static final int REMOVALS_PER_TICK = 256;
    private static final DeferredDiscardQueue<Entity> PENDING = new DeferredDiscardQueue<>();
    private static long tick;

    private RaidDeferredDiscard() {}

    public static void discardSoon(Entity entity, ServerLevel level, Predicate<Entity> stillOrphaned) {
        PENDING.schedule(entity, tick + DELAY_TICKS,
                () -> !entity.isRemoved() && entity.level() == level && stillOrphaned.test(entity),
                () -> RaidFaultBarrier.guard("deferred-discard:entity", entity::discard));
    }

    /** END_SERVER_TICK, outside the entity-tracking iteration that discovered the orphan. */
    public static void tick(MinecraftServer server) {
        PENDING.tick(++tick, REMOVALS_PER_TICK);
    }

    public static void onServerStopped() {
        PENDING.clear();
        tick = 0;
    }
}
