package com.cobbleraids.spawn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
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

    private RaidDeferredDiscard() {}

    public static void discardSoon(Entity entity, ServerLevel level) {
        MinecraftServer server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + DELAY_TICKS, () -> {
            if (!entity.isRemoved()) entity.discard();
        }));
    }
}
