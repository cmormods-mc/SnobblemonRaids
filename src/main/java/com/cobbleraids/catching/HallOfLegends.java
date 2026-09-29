package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.renown.RenownBoon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The server's wall of fame: one entry per {@link LegendKey} -- the first-ever defeat of a given
 * renowned title on a given species, anywhere on the server. Unlike {@link TrophyLedger} this is not
 * player-keyed; it is the one server-wide record in this mod, persisted the same way (overworld
 * {@link SavedData}), but as a single flat map rather than a map of maps.
 *
 * <p>Never overwritten once set: a title+species pairing can only ever be "first defeated" once, so
 * {@link #recordFirstDefeat} is the only write this store exposes, and it reports whether this call
 * was the one that actually claimed it -- the caller uses that to decide whether to announce.
 */
public final class HallOfLegends extends SavedData {
    private static final String FILE_ID = "cobbleraids_hall_of_legends";
    private static final Map<LegendKey, LegendEntry> LIVE = new ConcurrentHashMap<>();

    private Map<LegendKey, LegendEntry> loaded = new LinkedHashMap<>();

    // ---------------------------------------------------------------- live access

    /**
     * Claims {@code candidate}'s key if nothing already holds it. Returns {@code true} only for the
     * call that actually inserted it -- a race between two raids finishing with the same title and
     * species (unlikely, but not impossible) resolves to exactly one winner, decided by one atomic
     * map operation rather than a check followed by a separate write.
     */
    public static boolean recordFirstDefeat(MinecraftServer server, LegendEntry candidate) {
        LegendEntry[] inserted = {null};
        LIVE.computeIfAbsent(candidate.key(), ignored -> {
            inserted[0] = candidate;
            return candidate;
        });
        if (inserted[0] == null) return false;
        persist(server);
        return true;
    }

    public static Collection<LegendEntry> all() {
        return List.copyOf(LIVE.values());
    }

    private static void persist(MinecraftServer server) {
        if (server == null) return;
        get(server).update(LIVE);
    }

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        LIVE.clear();
        LIVE.putAll(get(server).take());
        if (!LIVE.isEmpty()) {
            RaidLog.info("Restored the Hall of Legends: " + LIVE.size() + " record(s).");
        }
    }

    /** Cleared with everything else per-server; see CobbleRaids' SERVER_STOPPED block. */
    public static void onServerStopped() {
        LIVE.clear();
    }

    // ---------------------------------------------------------------- persistence

    public static SavedData.Factory<HallOfLegends> factory() {
        return new SavedData.Factory<>(HallOfLegends::new, HallOfLegends::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, same reasoning as TrophyLedger: one file covers the whole server. */
    public static HallOfLegends get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<LegendKey, LegendEntry> take() {
        Map<LegendKey, LegendEntry> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    public void update(Map<LegendKey, LegendEntry> live) {
        loaded = new LinkedHashMap<>(live);
        setDirty();
    }

    // Package-private rather than private so a round-trip test can call it directly against a real
    // CompoundTag without bootstrapping Minecraft's registries -- every field is a primitive, UUID,
    // String or ResourceLocation, none of it registry-keyed.
    static HallOfLegends load(CompoundTag tag, HolderLookup.Provider registries) {
        HallOfLegends store = new HallOfLegends();
        ListTag rows = tag.getList("legends", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            String title = row.getString("title");
            ResourceLocation species = ResourceLocation.tryParse(row.getString("species"));
            if (title.isBlank() || species == null) continue; // dropped rather than failing the whole load

            RaidRarityTier tier;
            try {
                tier = RaidRarityTier.parse(row.getString("tier"));
            } catch (RuntimeException ignored) {
                continue; // a tier that no longer exists; drop this one row, keep the rest
            }
            RenownBoon boon = RenownBoon.decode(row.getString("boon")).orElse(null);
            if (boon == null) continue; // undecodable boon; drop this one row, keep the rest

            ListTag victorsTag = row.getList("victors", Tag.TAG_COMPOUND);
            List<UUID> victorIds = new ArrayList<>(victorsTag.size());
            List<String> victorNames = new ArrayList<>(victorsTag.size());
            for (int v = 0; v < victorsTag.size(); v++) {
                CompoundTag victor = victorsTag.getCompound(v);
                victorIds.add(victor.getUUID("id"));
                victorNames.add(victor.getString("name"));
            }
            if (victorIds.isEmpty()) continue; // a legend with no credited victor is not a legend

            LegendEntry entry = new LegendEntry(title, species, tier, boon, row.getLong("defeated_at"),
                    victorIds, victorNames);
            store.loaded.put(entry.key(), entry);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (LegendEntry entry : loaded.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("title", entry.title());
            row.putString("species", entry.species().toString());
            row.putString("tier", entry.tier().serializedName());
            row.putString("boon", entry.boon().encode());
            row.putLong("defeated_at", entry.defeatedAtEpochMs());

            ListTag victors = new ListTag();
            for (int v = 0; v < entry.victorIds().size(); v++) {
                CompoundTag victor = new CompoundTag();
                victor.putUUID("id", entry.victorIds().get(v));
                victor.putString("name", entry.victorNames().get(v));
                victors.add(victor);
            }
            row.put("victors", victors);

            rows.add(row);
        }
        tag.put("legends", rows);
        return tag;
    }
}
