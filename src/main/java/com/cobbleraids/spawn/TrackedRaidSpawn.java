package com.cobbleraids.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * One natural raid boss the scheduler is responsible for, tracked by identity and recorded position
 * rather than by a live entity reference.
 *
 * <p>Minecraft marks an entity removed with RemovalReason.UNLOADED_TO_CHUNK when its chunk unloads,
 * so a cached reference starts answering isRemoved() == true while the boss is still very much in
 * the world. Holding one made the scheduler drop unattended bosses from tracking the moment a player
 * walked far enough away for the chunk to unload -- which is exactly when the despawn timer was
 * supposed to start running. The boss then stayed forever, because RaidBossSpawner marks it
 * setPersistenceRequired(), and its slot against max_active_raids was quietly released.
 *
 * <p>The recorded position is what keeps min_distance_between_raids working for a boss whose chunk
 * is not loaded, which a live entity lookup cannot do.
 */
final class TrackedRaidSpawn {
    private final ResourceLocation definitionId;
    private final ResourceLocation dimension;
    private final BlockPos position;
    private final long spawnedAtTick;
    private final int despawnSeconds;
    private final int maxLifetimeSeconds;

    // Mutated once per second by the maintenance pass on the server thread, which is the only thing
    // that touches the tracker. A record here meant allocating a replacement every second for every
    // tracked boss just to advance a timer, and a wither method per mutable field.
    private long lastNearbyPlayerTick;
    private int expiryWarningsSent;

    TrackedRaidSpawn(ResourceLocation definitionId, ResourceLocation dimension, BlockPos position,
                     long spawnedAtTick, int despawnSeconds, int maxLifetimeSeconds) {
        this.definitionId = definitionId;
        this.dimension = dimension;
        this.position = position;
        this.spawnedAtTick = spawnedAtTick;
        this.lastNearbyPlayerTick = spawnedAtTick;
        this.despawnSeconds = despawnSeconds;
        this.maxLifetimeSeconds = maxLifetimeSeconds;
    }

    ResourceLocation definitionId() { return definitionId; }
    ResourceLocation dimension() { return dimension; }
    BlockPos position() { return position; }
    int despawnSeconds() { return despawnSeconds; }
    int maxLifetimeSeconds() { return maxLifetimeSeconds; }

    /** Seconds left on the total lifetime cap; negative once it has been exceeded. */
    long secondsLeft(long nowTick) {
        return maxLifetimeSeconds - (nowTick - spawnedAtTick) / 20L;
    }

    boolean idleLongEnoughToDespawn(long nowTick) {
        return nowTick - lastNearbyPlayerTick >= despawnSeconds * 20L;
    }

    void keepAlive(long nowTick) { lastNearbyPlayerTick = nowTick; }

    /**
     * The warning stage now due, or 0 for none. One warning at each threshold, tracked on the entry
     * so a player standing there for the whole countdown is told twice rather than once a second.
     */
    int claimExpiryWarning(long nowTick) {
        long secondsLeft = secondsLeft(nowTick);
        int stage = secondsLeft <= 10L ? 2 : secondsLeft <= 60L ? 1 : 0;
        if (stage == 0 || stage <= expiryWarningsSent) return 0;
        expiryWarningsSent = stage;
        return stage;
    }
}
