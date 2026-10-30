package com.cobbleraids.charges;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Server-thread-owned UUID accounts, saved independently of inventory, login and the radar item. */
public final class RaidChargeStore extends SavedData {
    private static final String FILE_ID = "cobbleraids_raid_charges";
    private final Map<UUID, RaidChargeAccount> accounts = new LinkedHashMap<>();
    public static RaidChargeStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaidChargeStore::new, RaidChargeStore::load, null), FILE_ID);
    }
    public RaidChargeAccount status(UUID id, long now) { return accounts.getOrDefault(id, RaidChargeAccount.FULL).at(now); }
    public static RaidChargeAccount status(ServerPlayer player) {
        return get(player.server).status(player.getUUID(), System.currentTimeMillis());
    }
    /** Reserve all entries before battle creation. Closing without commit restores every account. */
    public Reservation reserve(MinecraftServer server, Collection<UUID> ids) {
        long now = System.currentTimeMillis();
        Map<UUID, RaidChargeAccount> before = new LinkedHashMap<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            if (status(id, now).available() == 0) throw new IllegalStateException("A player has no raid charges remaining");
            before.put(id, accounts.get(id));
        }
        before.keySet().forEach(id -> accounts.put(id, status(id, now).spend(now)));
        try { persist(server); }
        catch (RuntimeException failure) { restore(before); throw failure; }
        return new Reservation(server, before);
    }
    private void restore(Map<UUID, RaidChargeAccount> before) {
        before.forEach((id, account) -> { if (account == null) accounts.remove(id); else accounts.put(id, account); });
        setDirty();
    }
    private void persist(MinecraftServer server) {
        setDirty();
        try {
            // SavedData.save catches write errors internally, so use an atomic write here:
            // a battle must not begin if its charge reservation could not reach disk.
            var directory=server.getWorldPath(LevelResource.ROOT).resolve("data");
            Files.createDirectories(directory);
            var target=directory.resolve(FILE_ID+".dat");
            var temporary=directory.resolve(FILE_ID+".dat.tmp");
            CompoundTag root=new CompoundTag();root.put("data",save(new CompoundTag(),server.registryAccess()));
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root,temporary);
            try { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
            setDirty(false);
        }
        catch (IOException failure) { throw new UncheckedIOException("Could not persist raid charges", failure); }
    }
    public final class Reservation implements AutoCloseable {
        private final MinecraftServer server;
        private final Map<UUID, RaidChargeAccount> before;
        private boolean finished;
        private Reservation(MinecraftServer server, Map<UUID, RaidChargeAccount> before) { this.server=server; this.before=before; }
        public void commit() { finished=true; }
        @Override public void close() {
            if (finished) return;
            finished=true; restore(before); persist(server);
        }
    }
    static RaidChargeStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidChargeStore store = new RaidChargeStore();
        ListTag rows=tag.getList("players", Tag.TAG_COMPOUND);
        for(int i=0;i<rows.size();i++) {
            CompoundTag row=rows.getCompound(i);
            if(!row.hasUUID("uuid")) {
                com.cobbleraids.RaidLog.warn("Ignoring a raid charge entry without a valid player UUID");
                continue;
            }
            UUID id=row.getUUID("uuid");
            try { store.accounts.put(id,new RaidChargeAccount(row.getInt("available"),row.getLong("next_refill"))); }
            catch(IllegalArgumentException invalid) {
                // Repair only this UUID; throwing here would make SavedData recreate the entire store.
                store.accounts.put(id,new RaidChargeAccount(0,System.currentTimeMillis()+RaidChargeAccount.REFILL_MILLIS));
                com.cobbleraids.RaidLog.warn("Invalid raid charge entry for {}; reset to zero with an hourly refill",id);
                store.setDirty();
            }
        }
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows=new ListTag();
        accounts.forEach((id,account) -> {
            CompoundTag row=new CompoundTag(); row.putUUID("uuid",id);
            row.putInt("available",account.available());row.putLong("next_refill",account.nextRefillAt());rows.add(row);
        });
        tag.put("players",rows);return tag;
    }
}
