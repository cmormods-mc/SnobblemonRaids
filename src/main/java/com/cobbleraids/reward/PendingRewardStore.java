package com.cobbleraids.reward;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Disk persistence for unclaimed raid rewards, attached to the overworld's data storage.
 *
 * Before this, the pending queue lived only in a static map, so every restart silently threw away
 * rewards nobody had claimed yet -- someone who logged off after a raid simply lost it.
 *
 * The reward contents are deliberately NOT serialized. A PendingRaidReward carries a snapshot of the
 * definition's reward config so a mid-raid /reload cannot rewrite an earned reward, but persisting
 * that whole structure would mean writing (and versioning) the entire reward schema on disk. Only
 * the definition id and the earned scalars are stored, and the reward config is re-resolved from the
 * registry on load; a claim saved across a restart therefore uses the definition as it stands when
 * the server comes back, which is also what an operator editing a datapack between sessions expects.
 * An entry whose definition no longer exists is dropped with a warning rather than failing the load.
 *
 * <p>What IS stored is the seed every roll derives from, which is how a claim survives a restart as
 * the same reward without a single ItemStack ever reaching disk.
 */
public final class PendingRewardStore extends SavedData {
    private static final String FILE_ID = "cobbleraids_pending_rewards";
    private static final String PLAYERS = "players";
    private static final String PLAYER_ID = "player";
    private static final String QUEUE = "queue";

    private Map<UUID, ArrayDeque<PendingRaidReward>> loaded = new LinkedHashMap<>();

    public static SavedData.Factory<PendingRewardStore> factory() {
        return new SavedData.Factory<>(PendingRewardStore::new, PendingRewardStore::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static PendingRewardStore get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<UUID, ArrayDeque<PendingRaidReward>> take() {
        Map<UUID, ArrayDeque<PendingRaidReward>> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    /** Replaces the stored snapshot. Called whenever the live queue changes. */
    public void update(Map<UUID, ArrayDeque<PendingRaidReward>> live) {
        loaded = new LinkedHashMap<>();
        for (Map.Entry<UUID, ArrayDeque<PendingRaidReward>> entry : live.entrySet()) {
            if (!entry.getValue().isEmpty()) loaded.put(entry.getKey(), new ArrayDeque<>(entry.getValue()));
        }
        setDirty();
    }

    private static PendingRewardStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PendingRewardStore store = new PendingRewardStore();
        ListTag players = tag.getList(PLAYERS, Tag.TAG_COMPOUND);
        int dropped = 0;
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID(PLAYER_ID);
            ArrayDeque<PendingRaidReward> queue = new ArrayDeque<>();
            ListTag entries = playerTag.getList(QUEUE, Tag.TAG_COMPOUND);
            for (int j = 0; j < entries.size(); j++) {
                PendingRaidReward pending = readEntry(entries.getCompound(j));
                if (pending == null) dropped++;
                else queue.addLast(pending);
            }
            if (!queue.isEmpty()) store.loaded.put(playerId, queue);
        }
        if (dropped > 0) {
            RaidLog.error("Dropped " + dropped
                    + " saved reward claim(s) whose raid definition is no longer loaded.");
        }
        return store;
    }

    private static PendingRaidReward readEntry(CompoundTag tag) {
        ResourceLocation definitionId = ResourceLocation.tryParse(tag.getString("definition"));
        if (definitionId == null) return null;
        RaidDefinition definition = RaidDefinitionRegistry.get(definitionId);
        if (definition == null) return null;
        RaidRarityTier tier;
        try {
            tier = RaidRarityTier.parse(tag.getString("tier"));
        } catch (IllegalArgumentException ex) {
            tier = definition.rarityTier();
        }
        // A claim written before seeds existed has none. Deriving one from the raid id rather than
        // drawing a fresh random keeps it stable across every subsequent load: an old claim settles
        // on one reward instead of rerolling on each restart until someone finally claims it.
        long seed = tag.contains("seed") ? tag.getLong("seed") : tag.getUUID("raid").hashCode();
        return new PendingRaidReward(
                tag.getUUID("raid"),
                definitionId,
                tier,
                definition.rewards(),
                tag.getDouble("contribution"),
                tag.getInt("bonus_rolls"),
                tag.getInt("elapsed_ticks"),
                tag.getInt("participants"),
                seed,
                // Absent on every claim saved before renown existed, which reads as "" -- an ordinary boss.
                tag.getString("renown"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, ArrayDeque<PendingRaidReward>> entry : loaded.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(PLAYER_ID, entry.getKey());
            ListTag entries = new ListTag();
            for (PendingRaidReward pending : entry.getValue()) entries.add(writeEntry(pending));
            playerTag.put(QUEUE, entries);
            players.add(playerTag);
        }
        tag.put(PLAYERS, players);
        return tag;
    }

    private static CompoundTag writeEntry(PendingRaidReward pending) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("raid", pending.raidId());
        tag.putString("definition", pending.definitionId().toString());
        tag.putString("tier", pending.rarityTier().serializedName());
        tag.putDouble("contribution", pending.contributionPercentage());
        tag.putInt("bonus_rolls", pending.contributionBonusRolls());
        tag.putInt("elapsed_ticks", pending.elapsedCombatTicks());
        tag.putInt("participants", pending.participantCount());
        tag.putLong("seed", pending.rewardSeed());
        if (pending.renowned()) tag.putString("renown", pending.renownTitle());
        return tag;
    }

    /** Flat view for admin listing. */
    public static List<PendingRaidReward> queueFor(Map<UUID, ArrayDeque<PendingRaidReward>> live, UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = live.get(playerId);
        return queue == null ? List.of() : new ArrayList<>(queue);
    }
}
