package com.cobbleraids.spawn;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SnapshotEntityCleanupTest {
    private static final class E implements EntityAccess {
        final int id;
        final UUID uuid = UUID.randomUUID();
        E(int id) { this.id = id; }
        public int getId() { return id; }
        public UUID getUUID() { return uuid; }
        public BlockPos blockPosition() { return new BlockPos(1, 64, 1); }
        public AABB getBoundingBox() { return new AABB(1, 64, 1, 2, 65, 2); }
        public void setLevelCallback(EntityInLevelCallback callback) {}
        public Stream<E> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<E> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) {}
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
    }

    @Test
    void snapshotsActualMinecraftLookupBeforeRemovingAcrossResizeBoundaries() {
        for (int count = 2; count <= 32; count++) {
            var lookup = new EntityLookup<E>();
            for (int i = 0; i < count; i++) lookup.add(new E(i));
            int removed = SnapshotEntityCleanup.removeMatching(lookup.getAllEntities(), e -> true, lookup::remove);
            assertEquals(count, removed);
            assertEquals(0, lookup.count(), "nothing skipped at lookup size " + count);
        }
    }

    @Test
    void onlySelectedBossesAreRemovedAndCallbackAdditionsAreSpared() {
        var lookup = new EntityLookup<E>();
        for (int i = 0; i < 32; i++) lookup.add(new E(i));
        int removed = SnapshotEntityCleanup.removeMatching(lookup.getAllEntities(), e -> e.id % 2 == 0, e -> {
            lookup.remove(e);
            lookup.add(new E(100 + e.id));
        });
        assertEquals(16, removed);
        assertEquals(32, lookup.count());
        for (int i = 0; i < 32; i++) {
            assertEquals(i % 2 != 0, lookup.getEntity(i) != null);
            if (i % 2 == 0) assertNotNull(lookup.getEntity(100 + i));
        }
    }
}
