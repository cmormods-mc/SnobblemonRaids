package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
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
 * Every player's permanent trophy room: one entry per species they have ever defeated, persisted to
 * the overworld's data storage.
 *
 * <p>Deliberately a separate store from {@link DefeatedBossSnapshots}, not a view onto it. That store
 * is spendable -- a buy-back or a reroll mutates or removes its one slot per species -- so a trophy
 * room built on top of it would lose an entry the moment a player cashed it in. This store never
 * removes an entry and never overwrites its captured stats; only {@code timesDefeated} moves.
 *
 * <p>Same shape as {@link DefeatedBossSnapshots}: a static map is the working copy, and the SavedData
 * below is its mirror on disk. No {@code pokemonNbt} blob here -- nothing is ever restored as a real
 * Pokemon from a trophy, only displayed, so the handful of primitive fields already carry everything
 * the trophy room needs.
 */
public final class TrophyLedger extends SavedData {
    private static final String FILE_ID = "cobbleraids_trophy_ledger";
    private static final Map<UUID, Map<ResourceLocation, TrophyEntry>> LIVE = new ConcurrentHashMap<>();

    private Map<UUID, Map<ResourceLocation, TrophyEntry>> loaded = new LinkedHashMap<>();

    // ---------------------------------------------------------------- live access

    public static Map<ResourceLocation, TrophyEntry> forPlayer(UUID playerId) {
        Map<ResourceLocation, TrophyEntry> perPlayer = LIVE.get(playerId);
        return perPlayer == null ? Map.of() : Map.copyOf(perPlayer);
    }

    /**
     * Records a defeat of {@code species}. The first sighting creates the entry and pins its stats;
     * every later one only increments {@link TrophyEntry#timesDefeated}, leaving the pinned stats
     * alone -- a trophy remembers the fight that earned it, not the most recent repeat.
     *
     * <p>{@code renownTitle} is empty for an ordinary defeat. The first renowned defeat of this
     * species pins {@link TrophyEntry#firstRenownTitle}; a later renowned repeat under a different
     * title still grows {@link TrophyEntry#timesRenownDefeated} but does not overwrite the pin.
     */
    public static void recordDefeat(MinecraftServer server, UUID playerId, ResourceLocation species,
                                    int level, boolean shiny, int ivPercent, int evPercent,
                                    RaidRarityTier tier, long timestampEpochMs, String renownTitle) {
        String title = renownTitle == null ? "" : renownTitle;
        Map<ResourceLocation, TrophyEntry> perPlayer =
                LIVE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        perPlayer.compute(species, (ignored, existing) -> existing == null
                ? new TrophyEntry(species, level, shiny, ivPercent, evPercent, tier, timestampEpochMs, 1, 0, 0, 0,
                        title.isEmpty() ? 0 : 1, title)
                : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                        existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                        existing.firstDefeatedAtEpochMs(), existing.timesDefeated() + 1,
                        existing.firstCapturedAtEpochMs(), existing.timesCaptured(),
                        existing.bestStabilizationScorePercent(),
                        existing.timesRenownDefeated() + (title.isEmpty() ? 0 : 1),
                        existing.firstRenownTitle().isEmpty() ? title : existing.firstRenownTitle()));
        persist(server);
    }

    /**
     * Records a Raid Capture Protocol success for {@code species}. The first capture creates the
     * entry (if a defeat has not already, since capturing a species always means having just defeated
     * it) and pins {@code firstCapturedAtEpochMs}; every later one only grows {@code timesCaptured}
     * and raises {@code bestStabilizationScorePercent} if this run beat it -- the same
     * "pin on first sighting, only counters move after" idiom {@link #recordDefeat} uses, kept
     * independent of it: a species defeated many times but captured only once still pins its
     * defeat stats from the very first fight, not this capture.
     */
    public static void recordCapture(MinecraftServer server, UUID playerId, ResourceLocation species,
                                     int level, boolean shiny, int ivPercent, int evPercent, RaidRarityTier tier,
                                     int stabilizationScorePercent, long timestampEpochMs) {
        Map<ResourceLocation, TrophyEntry> perPlayer =
                LIVE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        perPlayer.compute(species, (ignored, existing) -> existing == null
                ? new TrophyEntry(species, level, shiny, ivPercent, evPercent, tier, timestampEpochMs, 1,
                        timestampEpochMs, 1, stabilizationScorePercent, 0, "")
                : new TrophyEntry(existing.species(), existing.level(), existing.shiny(),
                        existing.ivPercent(), existing.evPercent(), existing.rarityTier(),
                        existing.firstDefeatedAtEpochMs(), existing.timesDefeated(),
                        existing.everCaptured() ? existing.firstCapturedAtEpochMs() : timestampEpochMs,
                        existing.timesCaptured() + 1,
                        Math.max(existing.bestStabilizationScorePercent(), stabilizationScorePercent),
                        existing.timesRenownDefeated(), existing.firstRenownTitle()));
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
            RaidLog.info("Restored trophy ledgers for " + LIVE.size() + " player(s).");
        }
    }

    /** Cleared with everything else per-server; see CobbleRaids' SERVER_STOPPED block. */
    public static void onServerStopped() {
        LIVE.clear();
    }

    // ---------------------------------------------------------------- persistence

    public static SavedData.Factory<TrophyLedger> factory() {
        return new SavedData.Factory<>(TrophyLedger::new, TrophyLedger::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static TrophyLedger get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<UUID, Map<ResourceLocation, TrophyEntry>> take() {
        Map<UUID, Map<ResourceLocation, TrophyEntry>> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    public void update(Map<UUID, Map<ResourceLocation, TrophyEntry>> live) {
        Map<UUID, Map<ResourceLocation, TrophyEntry>> copy = new LinkedHashMap<>();
        live.forEach((playerId, perPlayer) -> copy.put(playerId, new LinkedHashMap<>(perPlayer)));
        loaded = copy;
        setDirty();
    }

    // Package-private rather than private so a round-trip test can call it directly against a real
    // CompoundTag without bootstrapping Minecraft's registries -- every field is a primitive, UUID or
    // ResourceLocation, none of it registry-keyed.
    static TrophyLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        TrophyLedger store = new TrophyLedger();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");
            Map<ResourceLocation, TrophyEntry> perPlayer = new LinkedHashMap<>();
            ListTag speciesList = playerTag.getList("species", Tag.TAG_COMPOUND);
            for (int s = 0; s < speciesList.size(); s++) {
                CompoundTag row = speciesList.getCompound(s);
                ResourceLocation species = ResourceLocation.tryParse(row.getString("species"));
                if (species == null) continue; // dropped rather than failing the whole load
                RaidRarityTier tier;
                try {
                    tier = RaidRarityTier.parse(row.getString("tier"));
                } catch (RuntimeException ignored) {
                    continue; // a tier that no longer exists; drop this one entry, keep the rest
                }
                // Absent on every trophy saved before capturing existed, which reads as 0/0/0 -- "never
                // captured", exactly what TrophyEntry.everCaptured() expects for a real epoch millisecond.
                // Absent on every trophy saved before renown existed, which reads as 0/"" -- "never
                // renowned", exactly what TrophyEntry.everRenowned() expects.
                perPlayer.put(species, new TrophyEntry(
                        species, row.getInt("level"), row.getBoolean("shiny"),
                        row.getInt("iv_pct"), row.getInt("ev_pct"), tier,
                        row.getLong("first_defeated_at"), Math.max(1, row.getInt("times_defeated")),
                        row.getLong("first_captured_at"), row.getInt("times_captured"),
                        row.getInt("best_stabilization_pct"),
                        row.getInt("times_renown_defeated"), row.getString("first_renown_title")));
            }
            if (!perPlayer.isEmpty()) store.loaded.put(playerId, perPlayer);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Map<ResourceLocation, TrophyEntry>> entry : loaded.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", entry.getKey());
            ListTag speciesList = new ListTag();
            for (TrophyEntry trophy : entry.getValue().values()) {
                CompoundTag row = new CompoundTag();
                row.putString("species", trophy.species().toString());
                row.putInt("level", trophy.level());
                row.putBoolean("shiny", trophy.shiny());
                row.putInt("iv_pct", trophy.ivPercent());
                row.putInt("ev_pct", trophy.evPercent());
                row.putString("tier", trophy.rarityTier().serializedName());
                row.putLong("first_defeated_at", trophy.firstDefeatedAtEpochMs());
                row.putInt("times_defeated", trophy.timesDefeated());
                if (trophy.everCaptured()) {
                    row.putLong("first_captured_at", trophy.firstCapturedAtEpochMs());
                    row.putInt("times_captured", trophy.timesCaptured());
                    row.putInt("best_stabilization_pct", trophy.bestStabilizationScorePercent());
                }
                row.putInt("times_renown_defeated", trophy.timesRenownDefeated());
                if (trophy.everRenowned()) row.putString("first_renown_title", trophy.firstRenownTitle());
                speciesList.add(row);
            }
            playerTag.put("species", speciesList);
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }
}
