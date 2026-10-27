package com.cobbleraids.worldboss;

import com.cobbleraids.config.SavedDataFlush;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The World Boss that is running, on disk, so a restart picks the fight up where it was.
 *
 * <p>One file ({@code cobbleraids_world_boss.dat}) in the overworld's data storage, like the
 * pending-reward store. Holds the pool and every player's damage, where the boss stands, and two
 * flags that make the end of the fight safe to repeat: {@code defeatAnnounced} (listeners were told)
 * and {@code rewardsPaid} (Raid Points were paid). Each is saved <i>before</i> the step it guards is
 * repeated, so a crash between them replays only the step that did not finish.
 *
 * <p>An empty state ({@link #active} false) is the normal state of a server with no event running.
 */
public final class WorldBossState extends SavedData {
    private static final String FILE_ID = "cobbleraids_world_boss";

    private boolean active;
    private UUID bossId = new UUID(0L, 0L);
    private String definitionId = "";
    private String dimension = "minecraft:overworld";
    private double x;
    private double y;
    private double z;
    private long startedAtMillis;
    private long endsAtMillis;
    private long lastEndedAtMillis;
    private WorldBossPool pool;
    private boolean defeatAnnounced;
    private boolean rewardsPaid;
    private boolean arena;

    public static SavedData.Factory<WorldBossState> factory() {
        return new SavedData.Factory<>(WorldBossState::new, WorldBossState::load, DataFixTypes.LEVEL);
    }

    public static WorldBossState get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public void flush(MinecraftServer server) throws IOException {
        SavedDataFlush.flush(server, this, FILE_ID);
    }

    // ---- reads ----

    public boolean active() { return active; }
    public UUID bossId() { return bossId; }
    public String definitionId() { return definitionId; }
    public String dimension() { return dimension; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public long startedAtMillis() { return startedAtMillis; }
    /** Epoch millis when the event closes by itself, or 0 for never. */
    public long endsAtMillis() { return endsAtMillis; }
    /** Epoch millis when the previous World Boss ended, or 0 if none has. */
    public long lastEndedAtMillis() { return lastEndedAtMillis; }
    /** Null when no World Boss has been started. */
    public WorldBossPool pool() { return pool; }
    public boolean defeatAnnounced() { return defeatAnnounced; }
    public boolean rewardsPaid() { return rewardsPaid; }
    /** True when the boss started at an arena spawn marker: the zone then uses {@code arena.radius} and players can warp in. */
    public boolean arena() { return arena; }

    // ---- writes (each marks the state dirty) ----

    public void begin(UUID bossId, String definitionId, String dimension, double x, double y, double z,
                      long startedAtMillis, long endsAtMillis, WorldBossPool pool, boolean arena) {
        this.active = true;
        this.bossId = bossId;
        this.definitionId = definitionId;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.startedAtMillis = startedAtMillis;
        this.endsAtMillis = endsAtMillis;
        this.pool = pool;
        this.arena = arena;
        this.defeatAnnounced = false;
        this.rewardsPaid = false;
        setDirty();
    }

    public void markDefeatAnnounced() { defeatAnnounced = true; setDirty(); }

    public void markRewardsPaid() { rewardsPaid = true; setDirty(); }

    /** The pool changed (damage or growth); the caller flushes on its own schedule. */
    public void touch() { setDirty(); }

    /** The event is over. The pool and flags stay on disk for the record until the next begin. */
    public void finish(long nowMillis) { active = false; lastEndedAtMillis = nowMillis; setDirty(); }

    // ---- disk ----

    private static WorldBossState load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldBossState state = new WorldBossState();
        state.active = tag.getBoolean("active");
        state.lastEndedAtMillis = tag.getLong("last_ended");
        if (!tag.contains("boss")) return state;
        state.bossId = tag.getUUID("boss");
        state.definitionId = tag.getString("definition");
        state.dimension = tag.getString("dimension");
        state.x = tag.getDouble("x");
        state.y = tag.getDouble("y");
        state.z = tag.getDouble("z");
        state.startedAtMillis = tag.getLong("started");
        state.endsAtMillis = tag.getLong("ends");
        state.defeatAnnounced = tag.getBoolean("defeat_announced");
        state.rewardsPaid = tag.getBoolean("rewards_paid");
        state.arena = tag.getBoolean("arena");
        Map<UUID, Float> damage = new LinkedHashMap<>();
        ListTag list = tag.getList("damage", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            damage.put(entry.getUUID("player"), entry.getFloat("amount"));
        }
        float max = tag.getFloat("pool_max");
        if (max > 0f) {
            state.pool = new WorldBossPool(max, tag.getFloat("pool_current"), tag.getBoolean("pool_defeated"), damage);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("active", active);
        tag.putLong("last_ended", lastEndedAtMillis);
        if (pool == null) return tag;
        tag.putUUID("boss", bossId);
        tag.putString("definition", definitionId);
        tag.putString("dimension", dimension);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putLong("started", startedAtMillis);
        tag.putLong("ends", endsAtMillis);
        tag.putBoolean("defeat_announced", defeatAnnounced);
        tag.putBoolean("rewards_paid", rewardsPaid);
        tag.putBoolean("arena", arena);
        tag.putFloat("pool_max", pool.max());
        tag.putFloat("pool_current", pool.current());
        tag.putBoolean("pool_defeated", pool.isDefeated());
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Float> entry : pool.contributions().entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("player", entry.getKey());
            item.putFloat("amount", entry.getValue());
            list.add(item);
        }
        tag.put("damage", list);
        return tag;
    }
}
