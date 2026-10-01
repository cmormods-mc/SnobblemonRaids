package com.cobbleraids.stats;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPlayerRecords;
import com.cobbleraids.catching.TrophyEntry;
import com.cobbleraids.catching.TrophyLedger;
import com.cobbleraids.config.RaidRarityTier;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The statistics book, persisted to the overworld's data storage.
 *
 * <p>Same shape as the other raid stores: one static {@link StatsBook} is the working copy and this
 * SavedData is its mirror on disk. Saving reads the live book directly rather than a copy, so there
 * is nothing to keep in step; an event only has to mark the store dirty, which the next autosave
 * honours. A crash can lose a few minutes of counters, which is the same trade the raid records make
 * and for the same reason: nothing here is paid out against.
 *
 * <p>On the first start after the leaderboards exist, players with raid history are seeded from it,
 * so the boards are not empty on the day they launch. Only what the old records actually hold can be
 * recovered -- wins, wins by tier, captures and renowned defeats. Everything else (damage, Raid
 * Points earned, losses, streaks, speed) starts counting from that day.
 */
public final class RaidStatsStore extends SavedData {
    private static final String FILE_ID = "cobbleraids_stats";
    private static final StatsBook LIVE = new StatsBook();

    private StatsBook loaded = new StatsBook();

    // ---------------------------------------------------------------- live access

    public static StatsBook book() {
        return LIVE;
    }

    /** Marks the store dirty so the next autosave writes the live book. */
    public static void touch(MinecraftServer server) {
        if (server == null) return;
        get(server).setDirty();
    }

    /** The book this store was loaded with, before it goes live. For the round-trip test. */
    StatsBook loadedBook() {
        return loaded;
    }

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        StatsBook stored = get(server).loaded;
        LIVE.clear();
        LIVE.copyFrom(stored);
        if (!LIVE.backfilled()) backfill(server);
        if (!LIVE.players().isEmpty()) RaidLog.info("Restored raid statistics for " + LIVE.players().size() + " player(s).");
    }

    public static void onServerStopped() {
        LIVE.clear();
    }

    /**
     * Seeds every player with history from before this existed. Runs once, and marks the book so a
     * later start never does it again -- a player who has since played would otherwise be reset.
     */
    private static void backfill(MinecraftServer server) {
        int seeded = 0;
        for (Map.Entry<UUID, RaidPlayerRecord> entry : RaidPlayerRecords.all().entrySet()) {
            RaidPlayerRecord record = entry.getValue();
            if (record.raidsWon() <= 0 && record.bossesCaught() <= 0) continue;
            int renown = 0;
            for (TrophyEntry trophy : TrophyLedger.forPlayer(entry.getKey()).values()) {
                renown += trophy.timesRenownDefeated();
            }
            String name = server.getProfileCache() == null ? ""
                    : server.getProfileCache().get(entry.getKey()).map(profile -> profile.getName()).orElse("");
            LIVE.backfill(entry.getKey(), StatsBook.seedFromHistory(name, record.raidsWon(),
                    record.winsIn(RaidRarityTier.STARTER), record.winsIn(RaidRarityTier.POWERHOUSE),
                    record.winsIn(RaidRarityTier.LEGENDARY), record.winsIn(RaidRarityTier.MYTHICAL),
                    record.bossesCaught(), renown));
            seeded++;
        }
        LIVE.markBackfilled();
        touch(server);
        RaidLog.info("Raid statistics started from existing history for " + seeded + " player(s).");
    }

    // ---------------------------------------------------------------- persistence

    public static SavedData.Factory<RaidStatsStore> factory() {
        return new SavedData.Factory<>(RaidStatsStore::new, RaidStatsStore::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static RaidStatsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    // Package-private so a round-trip test can call it against a real CompoundTag with no registries.
    static RaidStatsStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidStatsStore store = new RaidStatsStore();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag row = players.getCompound(i);
            store.loaded.restore(row.getUUID("player"), new PlayerStats(row.getString("name"),
                    readBlock(row.getCompound("all")), row.getLong("week_window"),
                    readBlock(row.getCompound("week")), Math.max(0, row.getInt("streak"))));
        }
        CompoundTag totals = tag.getCompound("totals");
        store.loaded.restoreTotals(readTotals(totals.getCompound("all")),
                totals.contains("window") ? totals.getLong("window") : -1L,
                readTotals(totals.getCompound("week")), tag.getBoolean("backfilled"));
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, PlayerStats> entry : LIVE.players().entrySet()) {
            PlayerStats stats = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putUUID("player", entry.getKey());
            row.putString("name", stats.name());
            row.putInt("streak", stats.currentStreak());
            row.putLong("week_window", stats.weekWindow());
            row.put("all", writeBlock(stats.allTime()));
            row.put("week", writeBlock(stats.week()));
            players.add(row);
        }
        tag.put("players", players);
        CompoundTag totals = new CompoundTag();
        totals.putLong("window", LIVE.totalsWindow());
        totals.put("all", writeTotals(LIVE.allTimeTotals()));
        totals.put("week", writeTotals(LIVE.weekTotals(LIVE.totalsWindow())));
        tag.put("totals", totals);
        tag.putBoolean("backfilled", LIVE.backfilled());
        return tag;
    }

    static CompoundTag writeBlock(StatBlock block) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("joined", block.raidsJoined());
        tag.putInt("won", block.raidsWon());
        tag.putInt("lost", block.raidsLost());
        tag.putInt("fled", block.raidsFled());
        tag.putInt("w_starter", block.winsStarter());
        tag.putInt("w_powerhouse", block.winsPowerhouse());
        tag.putInt("w_legendary", block.winsLegendary());
        tag.putInt("w_mythical", block.winsMythical());
        tag.putLong("rp", block.rpEarned());
        tag.putLong("damage", block.totalDamage());
        tag.putLong("best_damage", block.bestRaidDamage());
        tag.putInt("caught", block.bossesCaught());
        tag.putInt("renown", block.renownDefeated());
        tag.putInt("streak", block.bestWinStreak());
        tag.putInt("fastest", block.fastestWinSeconds());
        return tag;
    }

    static StatBlock readBlock(CompoundTag tag) {
        return new StatBlock(tag.getInt("joined"), tag.getInt("won"), tag.getInt("lost"), tag.getInt("fled"),
                tag.getInt("w_starter"), tag.getInt("w_powerhouse"), tag.getInt("w_legendary"),
                tag.getInt("w_mythical"), tag.getLong("rp"), tag.getLong("damage"), tag.getLong("best_damage"),
                tag.getInt("caught"), tag.getInt("renown"), tag.getInt("streak"), tag.getInt("fastest"));
    }

    static CompoundTag writeTotals(RaidTotals totals) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("started", totals.started());
        tag.putInt("won", totals.won());
        tag.putInt("lost", totals.lost());
        tag.putInt("caught", totals.caught());
        tag.putLong("damage", totals.totalDamage());
        tag.putLong("rp", totals.rpPaid());
        return tag;
    }

    static RaidTotals readTotals(CompoundTag tag) {
        return new RaidTotals(tag.getInt("started"), tag.getInt("won"), tag.getInt("lost"), tag.getInt("caught"),
                tag.getLong("damage"), tag.getLong("rp"));
    }
}
