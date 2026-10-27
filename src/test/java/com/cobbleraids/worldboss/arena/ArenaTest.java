package com.cobbleraids.worldboss.arena;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleraids.worldboss.WorldBossConfig;
import com.cobbleraids.worldboss.WorldBossRules;
import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ArenaTest {
    @Test
    void spotFindsStandableGroundOnTheNearestRing() {
        // flat ground at y=10 (solid at y<=9... stand at y=10): solid below feet, two passable blocks above
        var spot = ArenaSpot.near(new BlockPos(0, 10, 0), new int[] {14, 18}, 6, p -> p.getY() <= 9, p -> p.getY() >= 10);
        assertTrue(spot.isPresent());
        assertEquals(10, spot.get().getY());
        assertEquals(14, Math.max(Math.abs(spot.get().getX()), Math.abs(spot.get().getZ())), "the first ring that works is used");
    }

    @Test
    void spotSkipsBlockedColumnsAndFailsOnNoGround() {
        assertTrue(ArenaSpot.near(new BlockPos(0, 10, 0), new int[] {14}, 6, p -> false, p -> true).isEmpty(), "nothing to stand on");
        assertTrue(ArenaSpot.near(new BlockPos(0, 10, 0), new int[] {14}, 6, p -> true, p -> false).isEmpty(), "no room to stand");
        // a pillar east of the centre blocks that direction only
        var spot = ArenaSpot.near(new BlockPos(0, 10, 0), new int[] {14}, 6, p -> p.getY() <= 9, p -> p.getY() >= 10 && !(p.getX() == 14 && p.getZ() == 0));
        assertTrue(spot.isPresent());
        assertFalse(spot.get().getX() == 14 && spot.get().getZ() == 0);
    }

    @Test
    void markersRoundTripThroughNbt() {
        ArenaMarkers markers = new ArenaMarkers();
        var a = new ArenaMarkers.Spot("cobbleraids:arena", new BlockPos(1, 2, 3));
        var b = new ArenaMarkers.Spot("minecraft:overworld", new BlockPos(-40, 70, 99));
        assertTrue(markers.add(ArenaMarkers.Kind.SPAWN, a));
        assertFalse(markers.add(ArenaMarkers.Kind.SPAWN, a), "the same spot twice is one marker");
        assertTrue(markers.add(ArenaMarkers.Kind.ENTRANCE, b));
        CompoundTag tag = markers.save(new CompoundTag(), null);
        ArenaMarkers loaded = ArenaMarkers.factory().deserializer().apply(tag, null);
        assertEquals(java.util.List.of(a), loaded.list(ArenaMarkers.Kind.SPAWN));
        assertEquals(java.util.List.of(b), loaded.list(ArenaMarkers.Kind.ENTRANCE));
        assertTrue(loaded.remove(ArenaMarkers.Kind.SPAWN, a));
        assertTrue(loaded.list(ArenaMarkers.Kind.SPAWN).isEmpty());
    }

    @Test
    void arenaConfigDefaultsParsesValidatesAndRoundTrips() {
        WorldBossConfig d = WorldBossConfig.defaults();
        assertTrue(d.arena().enabled());
        assertEquals(96.0, d.arena().radius());
        WorldBossConfig custom = WorldBossConfig.fromJson(JsonParser.parseString("{\"arena\":{\"enabled\":false,\"radius\":64}}").getAsJsonObject());
        assertFalse(custom.arena().enabled());
        assertEquals(64.0, custom.arena().radius());
        assertEquals(custom.arena(), WorldBossConfig.fromJson(custom.toJson()).arena());
        assertThrows(RuntimeException.class, () -> WorldBossConfig.fromJson(JsonParser.parseString("{\"arena\":{\"radius\":4}}").getAsJsonObject()));
    }

    @Test
    void zoneRadiusUsesTheArenaRadiusOnlyForAnArenaBoss() {
        WorldBossConfig c = WorldBossConfig.defaults();
        assertEquals(c.radius(), WorldBossRules.zoneRadius(null, c));
        var open = new com.cobbleraids.worldboss.WorldBossState();
        assertEquals(c.radius(), WorldBossRules.zoneRadius(open, c));
        open.begin(java.util.UUID.randomUUID(), "cobbleraids:gengar", "minecraft:overworld", 0, 0, 0, 0, 0, new com.cobbleraids.worldboss.WorldBossPool(100f), true);
        assertEquals(c.arena().radius(), WorldBossRules.zoneRadius(open, c));
        CompoundTag tag = open.save(new CompoundTag(), null);
        assertTrue(tag.getBoolean("arena"), "the arena flag is saved so a restart keeps the smaller zone");
    }
}
