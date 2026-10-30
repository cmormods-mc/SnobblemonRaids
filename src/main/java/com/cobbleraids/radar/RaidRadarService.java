package com.cobbleraids.radar;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.item.RaidRadarItem;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Server-thread only. Bounded terrain work; map patches go only to players holding an open radar. */
public final class RaidRadarService {
    private static final int SAMPLE_BUDGET = 64; // Across the whole server, not per viewer.
    private static final int FRAME_TICKS = 10;
    private static final int TERRAIN_TICKS = 200;
    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static List<View> active = List.of();
    private static int tick, sampleCursor;
    private static final byte[] PALETTE = {
            MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.LOWEST),
            MapColor.TERRACOTTA_CYAN.getPackedId(MapColor.Brightness.LOWEST),
            MapColor.COLOR_CYAN.getPackedId(MapColor.Brightness.LOW),
            MapColor.DIAMOND.getPackedId(MapColor.Brightness.NORMAL),
            MapColor.DIAMOND.getPackedId(MapColor.Brightness.HIGH),
            MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH),
            MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH),
            MapColor.COLOR_CYAN.getPackedId(MapColor.Brightness.LOWEST)
    };
    private RaidRadarService() {}

    private static final class View {
        final UUID playerId;
        final MapId mapId;
        final int playerEntityId;
        final ResourceLocation dimension;
        final int centerX, centerZ, baseY;
        byte[] background;
        byte[] sent;
        UUID targetId;
        List<RaidSpawnScheduler.RadarContact> targets = List.of();
        final int[] sampling = new int[RadarRenderer.GRID * RadarRenderer.GRID];
        int sampled;
        int nextTerrainTick;
        boolean hasTerrain;
        int[] hologramTerrain;
        int terrainRevision;
        int sentHologramRevision = -1;
        View(ServerPlayer player, MapId mapId) {
            this.playerId = player.getUUID();
            this.mapId = mapId;
            playerEntityId = player.getId();
            dimension = player.serverLevel().dimension().location();
            centerX = Math.floorDiv(player.getBlockX(), 64) * 64 + 32;
            centerZ = Math.floorDiv(player.getBlockZ(), 64) * 64 + 32;
            baseY = player.getBlockY();
            Arrays.fill(sampling, RadarRenderer.UNKNOWN);
            background = RadarRenderer.terrain(sampling, baseY);
            hologramTerrain = sampling.clone();
        }
        boolean matches(ServerPlayer player, MapId id) {
            return playerEntityId == player.getId() && mapId.equals(id) && dimension.equals(player.serverLevel().dimension().location())
                    && Math.abs(player.getX() - centerX) < 96 && Math.abs(player.getZ() - centerZ) < 96;
        }
    }

    public static void tick(MinecraftServer server) {
        tick++;
        if (tick % FRAME_TICKS == 0) refreshViews(server);
        if (active.isEmpty()) return;
        // Fair round-robin: adding viewers increases scan latency rather than the per-tick work limit.
        for (int i = 0; i < SAMPLE_BUDGET; i++) {
            if (sampleCursor >= active.size()) sampleCursor = 0;
            View view = active.get(sampleCursor++);
            ServerPlayer player = server.getPlayerList().getPlayer(view.playerId);
            if (player == null || !view.dimension.equals(player.serverLevel().dimension().location())) continue;
            if (!view.mapId.equals(heldRadar(player).get(DataComponents.MAP_ID))) continue;
            if (view.sampled == view.sampling.length) {
                if (tick < view.nextTerrainTick) continue;
                view.sampled = 0;
                Arrays.fill(view.sampling, RadarRenderer.UNKNOWN);
            }
            sample(view, player.serverLevel());
        }
    }

    private static ItemStack heldRadar(ServerPlayer player) {
        if (RaidRadarItem.isOpen(player.getMainHandItem())) return player.getMainHandItem();
        if (RaidRadarItem.isOpen(player.getOffhandItem())) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    private static void refreshViews(MinecraftServer server) {
        var present = new HashSet<UUID>();
        // Snapshot the bounded raid registries once per dimension per frame, never scan world entities.
        Map<ResourceLocation, List<RaidSpawnScheduler.RadarContact>> contacts = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack held = heldRadar(player);
            MapId id = held.get(DataComponents.MAP_ID);
            if (id == null) continue;
            present.add(player.getUUID());
            View view = VIEWS.get(player.getUUID());
            if (view == null || !view.matches(player, id)) {
                view = new View(player, id);
                VIEWS.put(player.getUUID(), view);
            }
            List<RaidSpawnScheduler.RadarContact> available = contacts.computeIfAbsent(view.dimension,
                    ignored -> contacts(player.serverLevel()));
            view.targets = available.stream().sorted(Comparator
                    .comparingDouble((RaidSpawnScheduler.RadarContact contact) -> horizontalDistance(player, contact))
                    .thenComparing(RaidSpawnScheduler.RadarContact::bossId)).toList();
            final UUID selected = view.targetId;
            RaidSpawnScheduler.RadarContact target = view.targets.stream()
                    .filter(contact -> contact.bossId().equals(selected)).findFirst().orElse(null);
            if (target == null && !view.targets.isEmpty()) target = view.targets.getFirst();
            view.targetId = target == null ? null : target.bossId();
            sendFrame(player, view, target);
        }
        VIEWS.keySet().retainAll(present);
        active = new ArrayList<>(VIEWS.values());
    }

    private static List<RaidSpawnScheduler.RadarContact> contacts(ServerLevel level) {
        var result = RaidSpawnScheduler.radarContacts(level);
        var ids = new HashSet<UUID>();
        for (var target : result) ids.add(target.bossId());
        for (var entry : RaidBossGlowService.trackedBosses().entrySet()) {
            if (!entry.getValue().equals(level.dimension().location()) || ids.contains(entry.getKey())) continue;
            if (!(level.getEntity(entry.getKey()) instanceof PokemonEntity boss) || boss.isRemoved()
                    || boss.isBattling() || RaidBossEntityMarker.isOwned(boss) || RaidBossEntityMarker.isNatural(boss)) continue;
            RaidBossEntityMarker.definitionId(boss).ifPresent(definition -> result.add(
                    new RaidSpawnScheduler.RadarContact(boss.getUUID(), definition, boss.position(), -1, false)));
        }
        result.removeIf(target -> {
            var definition = RaidDefinitionRegistry.get(target.definitionId());
            if (definition == null) return true;
            var boss = level.getEntity(target.bossId()) instanceof PokemonEntity pokemon ? pokemon : null;
            var lobby = RaidLobbyManager.get(boss);
            if (lobby == null || lobby.status() == com.cobbleraids.lobby.RaidRecruitmentRoster.Status.CANCELLED
                    || lobby.status() == com.cobbleraids.lobby.RaidRecruitmentRoster.Status.STARTED) {
                return !RaidLobbyManager.hasTimeToRecruit(target.secondsLeft(), definition);
            }
            return lobby.status() != com.cobbleraids.lobby.RaidRecruitmentRoster.Status.RECRUITING
                    || lobby.hasClosed(level.getGameTime()) || lobby.joinedCount() >= definition.recruitment().maxPlayers();
        });
        return result;
    }

    private static void sample(View view, ServerLevel level) {
        int index = view.sampled++;
        int x = view.centerX + (index % RadarRenderer.GRID - 16) * RadarRenderer.STEP;
        int z = view.centerZ + (index / RadarRenderer.GRID - 16) * RadarRenderer.STEP;
        // getChunkNow never requests a load or generation. World access stays on the server thread.
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        view.sampling[index] = chunk == null ? RadarRenderer.UNKNOWN
                : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (view.sampled == view.sampling.length) {
            view.background = RadarRenderer.terrain(view.sampling, view.baseY);
            view.hasTerrain = true;
            view.hologramTerrain = view.sampling.clone();
            view.terrainRevision++;
            view.nextTerrainTick = tick + TERRAIN_TICKS;
        }
    }

    private static double horizontalDistance(ServerPlayer player, RaidSpawnScheduler.RadarContact target) {
        return Math.hypot(player.getX() - target.position().x, player.getZ() - target.position().z);
    }

    private static void sendFrame(ServerPlayer player, View view, RaidSpawnScheduler.RadarContact target) {
        var playerPoint = RadarRenderer.project(player.getX() - view.centerX, player.getZ() - view.centerZ, player.getY() - view.baseY);
        var targetPoint = target == null ? null : RadarRenderer.project(target.position().x - view.centerX,
                target.position().z - view.centerZ, target.position().y - view.baseY);
        RaidDefinition definition = target == null ? null : RaidDefinitionRegistry.get(target.definitionId());
        String distance = target == null ? "" : Math.round(horizontalDistance(player, target)) + "M";
        String tier = definition == null ? "" : definition.rarityTier().serializedName().toUpperCase(java.util.Locale.ROOT);
        byte[] frame = RadarRenderer.frame(view.background, playerPoint, player.getYRot(), targetPoint, distance, tier, !view.hasTerrain);
        for (int i = 0; i < frame.length; i++) frame[i] = PALETTE[frame[i]];
        RadarMapPatch patch = RadarMapPatch.between(view.sent, frame);
        if (patch != null) {
            player.connection.send(new ClientboundMapItemDataPacket(view.mapId, (byte) 0, true, List.of(),
                    new MapItemSavedData.MapPatch(patch.x(), patch.y(), patch.width(), patch.height(), patch.pixels())));
            view.sent = frame;
        }
        if (RadarHologramNetworking.canShow(player)) {
            boolean fullTerrain = view.sentHologramRevision != view.terrainRevision;
            RadarHologramNetworking.send(player, new com.cobbleraids.radar.protocol.RadarHologramPayload(
                    view.mapId.id(), view.centerX, view.centerZ, view.baseY,
                    fullTerrain ? view.hologramTerrain : new int[0], target != null,
                    target == null ? 0 : target.position().x, target == null ? 0 : target.position().y,
                    target == null ? 0 : target.position().z,
                    definition == null ? "" : definition.species().getPath(), tier,
                    target == null ? -1 : target.secondsLeft(), target != null && target.lastKnown()));
            view.sentHologramRevision = view.terrainRevision;
        }
        if (tick % 40 == 0) {
            String status = target == null ? "No available raids in this dimension" :
                    definition.species().getPath() + " | " + distance + " | " + tier
                    + (target.lastKnown() ? " | last known position" : "")
                    + (target.secondsLeft() < 0 ? "" : " | " + target.secondsLeft() + "s remaining");
            player.displayClientMessage(Component.literal(status).withStyle(ChatFormatting.AQUA), true);
        }
    }

    public static void nextTarget(ServerPlayer player) {
        View view = VIEWS.get(player.getUUID());
        if (view == null || view.targets.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < view.targets.size(); i++) if (view.targets.get(i).bossId().equals(view.targetId)) index = i;
        view.targetId = view.targets.get((index + 1) % view.targets.size()).bossId();
    }
    public static void forget(UUID playerId) {
        VIEWS.remove(playerId);
        active = new ArrayList<>(VIEWS.values());
    }
    public static void clear() {
        VIEWS.clear(); active = List.of(); tick = 0; sampleCursor = 0;
    }
}
