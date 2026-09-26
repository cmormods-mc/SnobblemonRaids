package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.fault.RaidFaultBarrier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every player's saved, buyable-back defeated bosses, persisted to the overworld's data storage.
 *
 * <p>Same shape as {@link RaidPlayerRecords}: a static map is the working copy, and the SavedData
 * below is its mirror on disk. One slot per species per player -- a new defeat of an
 * already-saved species always replaces it outright, which is why there is no separate "update"
 * method: {@link #put} is used for both a fresh capture and a reroll's updated result.
 *
 * <p>Deliberately heavier than {@link RaidPlayerRecords}'s own convention of never writing rich
 * objects to disk ({@code PendingRewardStore}'s header comment explains why that store re-derives
 * rewards from a seed instead): a Pokemon's exact IVs/EVs/nature/moveset has no primitive-field
 * shortcut that preserves it, so the raw NBT this class stores per species is a deliberate, bounded
 * exception -- bounded because the slot count per player is capped by the number of raid species
 * that exist, not by how often anyone plays.
 */
public final class DefeatedBossSnapshots extends SavedData {
    private static final String FILE_ID = "cobbleraids_boss_snapshots";
    private static final Map<UUID, Map<ResourceLocation, BossSnapshot>> LIVE = new ConcurrentHashMap<>();

    private Map<UUID, Map<ResourceLocation, BossSnapshot>> loaded = new LinkedHashMap<>();

    // ---------------------------------------------------------------- live access

    public static Map<ResourceLocation, BossSnapshot> forPlayer(UUID playerId) {
        Map<ResourceLocation, BossSnapshot> perPlayer = LIVE.get(playerId);
        return perPlayer == null ? Map.of() : Map.copyOf(perPlayer);
    }

    public static BossSnapshot get(UUID playerId, ResourceLocation species) {
        Map<ResourceLocation, BossSnapshot> perPlayer = LIVE.get(playerId);
        return perPlayer == null ? null : perPlayer.get(species);
    }

    /** Saves a snapshot for this player and species, replacing whatever was already there. */
    public static void put(MinecraftServer server, UUID playerId, BossSnapshot snapshot) {
        LIVE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(snapshot.species(), snapshot);
        persist(server);
    }

    /** Removes one slot, e.g. once its snapshot has been bought back. */
    public static void remove(MinecraftServer server, UUID playerId, ResourceLocation species) {
        Map<ResourceLocation, BossSnapshot> perPlayer = LIVE.get(playerId);
        if (perPlayer == null) return;
        if (perPlayer.remove(species) == null) return;
        // Tidiness, not correctness: an empty per-player map left behind is harmless but permanent
        // clutter in LIVE for the life of the server otherwise.
        if (perPlayer.isEmpty()) LIVE.remove(playerId, perPlayer);
        persist(server);
    }

    private static void persist(MinecraftServer server) {
        if (server == null) return;
        get(server).update(LIVE);
    }

    /**
     * Marks dirty <em>and</em> writes immediately, for a change paired with something that already
     * reached the player's inventory -- same reason {@link RaidPlayerRecords#flush} exists: a
     * purchase removes a slot at the same moment it hands over a real Pokemon, and inventories
     * reach disk on logout while SavedData waits for an autosave. Buy, log out, crash the server,
     * and the slot would still be there for a Pokemon the player already has.
     */
    public static void flush(MinecraftServer server) {
        persist(server);
        if (server != null) {
            RaidFaultBarrier.guard("boss-snapshots-flush", () -> server.overworld().getDataStorage().save());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        LIVE.clear();
        LIVE.putAll(get(server).take());
        if (!LIVE.isEmpty()) {
            RaidLog.info("Restored defeated-boss snapshots for " + LIVE.size() + " player(s).");
        }
    }

    /** Cleared with everything else per-server; see CobbleRaids' SERVER_STOPPED block. */
    public static void onServerStopped() {
        LIVE.clear();
    }

    // ---------------------------------------------------------------- persistence

    public static SavedData.Factory<DefeatedBossSnapshots> factory() {
        return new SavedData.Factory<>(DefeatedBossSnapshots::new, DefeatedBossSnapshots::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static DefeatedBossSnapshots get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<UUID, Map<ResourceLocation, BossSnapshot>> take() {
        Map<UUID, Map<ResourceLocation, BossSnapshot>> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    public void update(Map<UUID, Map<ResourceLocation, BossSnapshot>> live) {
        Map<UUID, Map<ResourceLocation, BossSnapshot>> copy = new LinkedHashMap<>();
        live.forEach((playerId, perPlayer) -> copy.put(playerId, new LinkedHashMap<>(perPlayer)));
        loaded = copy;
        setDirty();
    }

    // Package-private rather than private so a round-trip test can call it directly against a real
    // CompoundTag without bootstrapping Minecraft's registries -- every field except the nested
    // Pokemon blob is a primitive, UUID or ResourceLocation, none of it registry-keyed. The blob
    // itself is written and read back opaquely: this class never interprets it.
    static DefeatedBossSnapshots load(CompoundTag tag, HolderLookup.Provider registries) {
        DefeatedBossSnapshots store = new DefeatedBossSnapshots();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");
            Map<ResourceLocation, BossSnapshot> perPlayer = new LinkedHashMap<>();
            ListTag speciesList = playerTag.getList("species", Tag.TAG_COMPOUND);
            for (int s = 0; s < speciesList.size(); s++) {
                CompoundTag row = speciesList.getCompound(s);
                ResourceLocation species = ResourceLocation.tryParse(row.getString("species"));
                if (species == null) continue; // dropped rather than failing the whole load
                RaidRarityTier tier;
                try {
                    tier = RaidRarityTier.parse(row.getString("tier"));
                } catch (RuntimeException ignored) {
                    continue; // a tier that no longer exists; drop this one slot, keep the rest
                }
                perPlayer.put(species, new BossSnapshot(
                        species, row.getInt("level"), row.getBoolean("shiny"),
                        row.getInt("iv_pct"), row.getInt("ev_pct"), tier,
                        row.getCompound("pokemon"), row.getLong("captured_at")));
            }
            if (!perPlayer.isEmpty()) store.loaded.put(playerId, perPlayer);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Map<ResourceLocation, BossSnapshot>> entry : loaded.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", entry.getKey());
            ListTag speciesList = new ListTag();
            for (BossSnapshot snapshot : entry.getValue().values()) {
                CompoundTag row = new CompoundTag();
                row.putString("species", snapshot.species().toString());
                row.putInt("level", snapshot.level());
                row.putBoolean("shiny", snapshot.shiny());
                row.putInt("iv_pct", snapshot.ivPercent());
                row.putInt("ev_pct", snapshot.evPercent());
                row.putString("tier", snapshot.rarityTier().serializedName());
                row.put("pokemon", snapshot.pokemonNbt());
                row.putLong("captured_at", snapshot.capturedAtEpochMs());
                speciesList.add(row);
            }
            playerTag.put("species", speciesList);
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }
}
