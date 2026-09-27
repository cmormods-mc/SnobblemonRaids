package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
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
 * Disk persistence for Raid Capture Protocol sessions, attached to the overworld's data storage.
 *
 * <p>Same shape as {@link com.cobbleraids.reward.PendingRewardStore}: only sessions that are not yet
 * fully settled are ever held here. A session is removed the instant its outcome is fully spent --
 * its banked Raid Points credited, or its Pokemon actually delivered -- so a restart can never
 * re-offer a choice already made or re-roll a capture already decided. A successful roll whose
 * delivery is still {@code PENDING_RETRY} stays, NBT and all, because the Pokemon it already rolled
 * has not reached the player yet. An {@code AWAITING_CLAIM} session (offered at victory, not yet
 * claimed) also stays indefinitely, the same way an unclaimed reward itself does.
 *
 * <p>A restored session does not need its raid definition to resolve at load time at all:
 * species/form/level/shiny, every scored pulse, and the banked points figure (a plain {@code int} by
 * the time it is banked, computed once at claim time) all live on the session itself, so a still-open
 * attempt (or an already-successful one awaiting delivery) is fully self-contained. A row whose tier
 * or species no longer parses at all is still dropped with a warning, the same defensive-read
 * convention {@code TrophyLedger} uses.
 */
public final class RaidCaptureSessionStore extends SavedData {
    private static final String FILE_ID = "cobbleraids_capture_sessions";

    private Map<UUID, ArrayDeque<RaidCaptureSession>> loaded = new LinkedHashMap<>();

    public static SavedData.Factory<RaidCaptureSessionStore> factory() {
        return new SavedData.Factory<>(RaidCaptureSessionStore::new, RaidCaptureSessionStore::load, DataFixTypes.LEVEL);
    }

    /** Overworld storage, so one file covers the server rather than one per dimension. */
    public static RaidCaptureSessionStore get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public Map<UUID, ArrayDeque<RaidCaptureSession>> take() {
        Map<UUID, ArrayDeque<RaidCaptureSession>> result = loaded;
        loaded = new LinkedHashMap<>();
        return result;
    }

    /** Replaces the stored snapshot. Called whenever the live sessions change. */
    public void update(Map<UUID, ArrayDeque<RaidCaptureSession>> live) {
        loaded = new LinkedHashMap<>();
        for (Map.Entry<UUID, ArrayDeque<RaidCaptureSession>> entry : live.entrySet()) {
            if (!entry.getValue().isEmpty()) loaded.put(entry.getKey(), new ArrayDeque<>(entry.getValue()));
        }
        setDirty();
    }

    // Package-private rather than private so a round-trip test can call it directly against a real
    // CompoundTag without bootstrapping Minecraft's registries -- every field is a primitive, UUID,
    // ResourceLocation or NBT tag, none of it registry-keyed.
    static RaidCaptureSessionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidCaptureSessionStore store = new RaidCaptureSessionStore();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        int dropped = 0;
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");
            ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
            ListTag entries = playerTag.getList("queue", Tag.TAG_COMPOUND);
            for (int j = 0; j < entries.size(); j++) {
                RaidCaptureSession session = readEntry(entries.getCompound(j));
                if (session == null) dropped++;
                else queue.addLast(session);
            }
            if (!queue.isEmpty()) store.loaded.put(playerId, queue);
        }
        if (dropped > 0) {
            RaidLog.error("Dropped " + dropped
                    + " saved capture session(s) with an unparseable tier, species or state.");
        }
        return store;
    }

    private static RaidCaptureSession readEntry(CompoundTag tag) {
        ResourceLocation definitionId = ResourceLocation.tryParse(tag.getString("definition"));
        if (definitionId == null) return null;
        RaidRarityTier tier;
        try {
            tier = RaidRarityTier.parse(tag.getString("tier"));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        ResourceLocation species = ResourceLocation.tryParse(tag.getString("species"));
        if (species == null) return null;
        RaidCaptureSession.CapturePhase phase;
        RaidCaptureSession.DeliveryState delivery;
        try {
            phase = RaidCaptureSession.CapturePhase.valueOf(tag.getString("phase"));
            delivery = RaidCaptureSession.DeliveryState.valueOf(tag.getString("delivery"));
        } catch (IllegalArgumentException ex) {
            return null;
        }

        return new RaidCaptureSession(
                tag.getUUID("player"),
                tag.getUUID("raid"),
                definitionId,
                tier,
                species,
                tag.contains("form") ? tag.getString("form") : null,
                tag.getInt("level"),
                tag.getBoolean("shiny"),
                phase,
                tag.getInt("banked_points"),
                tag.getLong("choice_deadline"),
                tag.getLong("step_started"),
                tag.getLong("sequence_deadline"),
                tag.getDouble("pulse1"),
                tag.getDouble("pulse2"),
                tag.getDouble("pulse3"),
                tag.getInt("next_pulse"),
                tag.getDouble("throw_score"),
                tag.getBoolean("rolled"),
                tag.getBoolean("roll_succeeded"),
                tag.getDouble("final_chance"),
                delivery,
                tag.contains("pokemon") ? tag.getCompound("pokemon") : null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, ArrayDeque<RaidCaptureSession>> entry : loaded.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", entry.getKey());
            ListTag entries = new ListTag();
            for (RaidCaptureSession session : entry.getValue()) entries.add(writeEntry(session));
            playerTag.put("queue", entries);
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }

    private static CompoundTag writeEntry(RaidCaptureSession session) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player", session.playerId());
        tag.putUUID("raid", session.raidId());
        tag.putString("definition", session.definitionId().toString());
        tag.putString("tier", session.tier().serializedName());
        tag.putString("species", session.species().toString());
        if (session.form() != null) tag.putString("form", session.form());
        tag.putInt("level", session.level());
        tag.putBoolean("shiny", session.shiny());
        tag.putString("phase", session.phase().name());
        tag.putInt("banked_points", session.bankedRaidPoints());
        tag.putLong("choice_deadline", session.choiceDeadlineEpochMs());
        tag.putLong("step_started", session.currentStepStartedAtEpochMs());
        tag.putLong("sequence_deadline", session.sequenceDeadlineEpochMs());
        tag.putDouble("pulse1", session.pulse1Score());
        tag.putDouble("pulse2", session.pulse2Score());
        tag.putDouble("pulse3", session.pulse3Score());
        tag.putInt("next_pulse", session.nextPulseIndex());
        tag.putDouble("throw_score", session.throwScore());
        tag.putBoolean("rolled", session.rolled());
        tag.putBoolean("roll_succeeded", session.rollSucceeded());
        tag.putDouble("final_chance", session.finalChanceUsed());
        tag.putString("delivery", session.delivery().name());
        if (session.pokemonNbt() != null) tag.put("pokemon", session.pokemonNbt());
        return tag;
    }
}
