package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * Live raid history for every player, persisted to the overworld's data storage.
 *
 * <p>Same shape as PendingRewardStore: a static map is the working copy, and the SavedData below is
 * its mirror on disk. Records must outlive a restart or every catch mechanic built on them resets
 * with the server, which is the one failure that would make the whole idea untrustworthy.
 */
public final class RaidPlayerRecords extends SavedData {
    private static final String FILE_ID = "cobbleraids_player_records";
    private static final Map<UUID, RaidPlayerRecord> LIVE = new ConcurrentHashMap<>();

    private Map<UUID, RaidPlayerRecord> loaded = new LinkedHashMap<>();

    // ---------------------------------------------------------------- live access

    public static RaidPlayerRecord get(UUID playerId) {
        return LIVE.getOrDefault(playerId, RaidPlayerRecord.EMPTY);
    }

    public static Map<UUID, RaidPlayerRecord> all() {
        return Map.copyOf(LIVE);
    }

    /**
     * Records a won raid for every victor and persists once.
     *
     * <p>Deliberately takes the whole party rather than one player at a time. Persisting copies the
     * entire record map, which grows with every distinct player the server has ever seen -- so a
     * four-player victory used to do four full copies of a map that keeps growing all season, on the
     * server thread, inside raid finalization. Once per raid is the same durability for a quarter of
     * the work, and it stays a quarter as the map grows.
     */
    public static void recordWins(MinecraftServer server, RaidRarityTier tier,
                                  ResourceLocation definitionId, Map<UUID, Double> contributionByPlayer) {
        if (contributionByPlayer.isEmpty()) return;
        for (Map.Entry<UUID, Double> entry : contributionByPlayer.entrySet()) {
            double contribution = entry.getValue();
            LIVE.merge(entry.getKey(), RaidPlayerRecord.EMPTY.withWin(tier, definitionId, contribution),
                    (existing, ignored) -> existing.withWin(tier, definitionId, contribution));
        }
        persist(server);
    }

    public static void recordCatch(MinecraftServer server, UUID playerId) {
        LIVE.merge(playerId, RaidPlayerRecord.EMPTY.withCatch(),
                (existing, ignored) -> existing.withCatch());
        persist(server);
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
            RaidLog.info("Restored raid records for " + LIVE.size() + " player(s).");
        }
    }

    /** Cleared with everything else per-server; see CobbleRaids' SERVER_STOPPED block. */
    public static void onServerStopped() {
        LIVE.clear();
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Records one mega-capable raid claim, and whether it produced a stone.
     *
     * <p>The counter only moves on raids that could have dropped one: counting a Pidgeot raid
     * against a player's bad luck would make the guarantee mean nothing, since 30% of starter
     * spawns and nearly every legendary carry no stone at all.
     */
    public static void recordMegaCapableClaim(MinecraftServer server, UUID playerId, boolean gotStone) {
        LIVE.compute(playerId, (ignored, existing) -> {
            RaidPlayerRecord current = existing == null ? RaidPlayerRecord.EMPTY : existing;
            return new RaidPlayerRecord(current.raidsWon(), current.winsByTier(),
                    current.defeatsBySpecies(), current.totalContribution(), current.bossesCaught(),
                    gotStone ? 0 : current.raidsSinceMegaStone() + 1);
        });
        persist(server);
    }

    public static SavedData.Factory<RaidPlayerRecords> factory() {
        return new SavedData.Factory<>(RaidPlayerRecords::new, RaidPlayerRecords::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static RaidPlayerRecords get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<UUID, RaidPlayerRecord> take() {
        Map<UUID, RaidPlayerRecord> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    public void update(Map<UUID, RaidPlayerRecord> live) {
        loaded = new LinkedHashMap<>(live);
        setDirty();
    }

    private static RaidPlayerRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidPlayerRecords store = new RaidPlayerRecords();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            Map<RaidRarityTier, Integer> tiers = new LinkedHashMap<>();
            CompoundTag tierTag = playerTag.getCompound("tiers");
            for (String key : tierTag.getAllKeys()) {
                try {
                    tiers.put(RaidRarityTier.parse(key), tierTag.getInt(key));
                } catch (RuntimeException ignored) {
                    // A tier that no longer exists is dropped rather than failing the whole load.
                }
            }
            Map<ResourceLocation, Integer> species = new LinkedHashMap<>();
            CompoundTag speciesTag = playerTag.getCompound("species");
            for (String key : speciesTag.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id != null) species.put(id, speciesTag.getInt(key));
            }
            store.loaded.put(playerTag.getUUID("player"), new RaidPlayerRecord(
                    playerTag.getInt("wins"), tiers, species,
                    playerTag.getDouble("contribution"), playerTag.getInt("caught"),
                    playerTag.getInt("since_mega")));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, RaidPlayerRecord> entry : loaded.entrySet()) {
            RaidPlayerRecord record = entry.getValue();
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", entry.getKey());
            playerTag.putInt("wins", record.raidsWon());
            playerTag.putDouble("contribution", record.totalContribution());
            playerTag.putInt("caught", record.bossesCaught());
            playerTag.putInt("since_mega", record.raidsSinceMegaStone());
            CompoundTag tiers = new CompoundTag();
            record.winsByTier().forEach((tier, count) ->
                    tiers.putInt(tier.serializedName().toLowerCase(Locale.ROOT), count));
            playerTag.put("tiers", tiers);
            CompoundTag species = new CompoundTag();
            record.defeatsBySpecies().forEach((id, count) -> species.putInt(id.toString(), count));
            playerTag.put("species", species);
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }
}
