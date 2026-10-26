package com.cobbleraids.worldboss;

import com.cobbleraids.worldboss.DomeGeometry.Panel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the World Boss zone as a faceted glass dome. See {@link DomeGeometry} for the shape.
 *
 * <p>Each panel is an {@code item_display} entity that is never added to the world: the server builds it, and sends the add and data packets
 * only to players near that panel. So the dome costs the world nothing (no entity ticking, saving or tracking) and a player far away is sent
 * nothing. The item is vanilla with a {@code custom_model_data} number that AscensionLib's resource pack maps to an animated glass triangle
 * (see {@code ItemModelOverrides.DOME_*} there); a client without the pack sees an ender eye model rendered flat, which is why the particle
 * wall can stay on as a fallback.
 *
 * <p>Everything runs on the server thread.
 */
final class WorldBossDome {

    /** Vanilla item and model numbers of the pack's panels: three swirl variants, then the flash. Frozen with AscensionLib's numbers. */
    private static final int FIRST_MODEL = 8201;
    private static final int FLASH_MODEL = FIRST_MODEL + DomeGeometry.SWIRL_VARIANTS;
    /** A panel is sent to a player within this many blocks of it, and kept until they are this far (the margin stops flicker). */
    private static final double NEAR = 150.0;
    private static final double FAR = 190.0;
    /** A crossing flash lasts this many game ticks; panels flashed per crossing. */
    private static final long FLASH_TICKS = 14L;
    private static final int FLASH_PANELS = 3;
    /** A hemisphere's base ring sits this far below the boss's feet, so terrain that dips a little does not leave a gap under it. */
    private static final double SINK = 2.0;

    /** A flash overlay in front of a panel, and who was sent it. */
    private record Flash(Entity entity, long expiresTick, Set<UUID> viewers) {}

    private static String builtKey = "";
    private static ServerLevel builtLevel;
    private static Vec3 centre = Vec3.ZERO;
    private static List<Panel> panels = List.of();
    private static Entity[] entities = new Entity[0];
    private static final Map<UUID, Set<Integer>> SHOWN = new HashMap<>();
    private static final List<Flash> FLASHES = new ArrayList<>();

    private WorldBossDome() {}

    /** Sends each player the panels near them and takes back the ones they have left behind. Called a few times a second. */
    static void tick(MinecraftServer server, WorldBossState state, WorldBossConfig config) {
        WorldBossConfig.Dome dome = config.dome();
        ResourceLocation dimensionId = ResourceLocation.tryParse(state.dimension());
        ServerLevel level = dimensionId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (!dome.enabled() || level == null) {
            clear(server);
            return;
        }
        ensureBuilt(level, state, config);
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (player.level() != level) {
                SHOWN.remove(player.getUUID());   // the client dropped them with the old dimension
                continue;
            }
            Set<Integer> have = SHOWN.computeIfAbsent(player.getUUID(), id -> new HashSet<>());
            Set<Integer> want = DomeGeometry.visible(panels, have, player.getX() - centre.x, player.getY() - centre.y, player.getZ() - centre.z, NEAR, FAR);
            for (int index : want) {
                if (!have.contains(index)) sendAdd(player, entities[index]);
            }
            List<Integer> gone = new ArrayList<>();
            for (int index : have) {
                if (!want.contains(index)) gone.add(index);
            }
            if (!gone.isEmpty()) sendRemove(player, gone.stream().mapToInt(i -> entities[i].getId()).toArray());
            SHOWN.put(player.getUUID(), want);
        }
        SHOWN.keySet().retainAll(online);
        expireFlashes(server, level.getGameTime());
    }

    /** Takes the dome away from everyone and forgets it: the boss ended, the dome was turned off, or the server is stopping. */
    static void clear(MinecraftServer server) {
        if (!SHOWN.isEmpty() || !FLASHES.isEmpty()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Set<Integer> have = SHOWN.get(player.getUUID());
                if (have != null && !have.isEmpty()) sendRemove(player, have.stream().mapToInt(i -> entities[i].getId()).toArray());
            }
            for (Flash flash : FLASHES) remove(server, flash);
        }
        reset();
    }

    /** For the server-stopped hook: nobody is left to tell. */
    static void reset() {
        SHOWN.clear();
        FLASHES.clear();
        builtKey = "";
        builtLevel = null;
        panels = List.of();
        entities = new Entity[0];
    }

    /** A flash and a chill when a player crosses the wall: the panels nearest to them light up for a moment. */
    static void ripple(ServerPlayer player, boolean entering) {
        if (panels.isEmpty() || builtLevel == null || player.level() != builtLevel) return;
        Vec3 at = player.position();
        long now = builtLevel.getGameTime();
        for (int index : DomeGeometry.nearest(panels, at.x - centre.x, at.y - centre.y, at.z - centre.z, FLASH_PANELS)) {
            Panel panel = panels.get(index);
            // in front of the panel on the player's side, so it is not hidden by (or fighting with) the panel it lights up
            double side = Math.signum(panel.nx() * (at.x - centre.x - panel.x()) + panel.ny() * (at.y - centre.y - panel.y())
                    + panel.nz() * (at.z - centre.z - panel.z()));
            double push = 0.4 * (side == 0 ? 1 : side);
            Entity flash = make(builtLevel, panel, FLASH_MODEL, panel.nx() * push, panel.ny() * push, panel.nz() * push);
            Set<UUID> viewers = new HashSet<>();
            for (var shown : SHOWN.entrySet()) {
                if (!shown.getValue().contains(index)) continue;
                ServerPlayer viewer = builtLevel.getServer().getPlayerList().getPlayer(shown.getKey());
                if (viewer == null) continue;
                sendAdd(viewer, flash);
                viewers.add(shown.getKey());
            }
            FLASHES.add(new Flash(flash, now + FLASH_TICKS, viewers));
        }
        player.connection.send(new ClientboundSoundPacket(SoundEvents.SOUL_ESCAPE, SoundSource.AMBIENT, at.x, at.y, at.z,
                1.0f, entering ? 1.3f : 0.7f, player.getRandom().nextLong()));
        builtLevel.sendParticles(player, ParticleTypes.SCULK_SOUL, true, at.x, at.y + 1.0, at.z, 18, 0.6, 0.9, 0.6, 0.02);
        builtLevel.sendParticles(player, ParticleTypes.SOUL, true, at.x, at.y + 1.0, at.z, 10, 0.6, 0.9, 0.6, 0.05);
    }

    // ---- building the entities ----

    private static void ensureBuilt(ServerLevel level, WorldBossState state, WorldBossConfig config) {
        WorldBossConfig.Dome dome = config.dome();
        String key = state.dimension() + "|" + state.x() + "|" + state.y() + "|" + state.z() + "|" + config.radius() + "|" + dome.height() + "|" + dome.detail() + "|" + dome.full();
        if (key.equals(builtKey) && level == builtLevel) return;
        SHOWN.clear();
        FLASHES.clear();
        builtKey = key;
        builtLevel = level;
        centre = new Vec3(state.x(), dome.full() ? state.y() : state.y() - SINK, state.z());
        panels = DomeGeometry.panels(config.radius(), dome.height(), dome.detail(), dome.full());
        entities = new Entity[panels.size()];
        for (int i = 0; i < panels.size(); i++) {
            Panel panel = panels.get(i);
            entities[i] = make(level, panel, FIRST_MODEL + panel.variant(), 0, 0, 0);
        }
    }

    /** An unattached item display showing one panel, offset from its place by (dx, dy, dz). */
    private static Entity make(ServerLevel level, Panel panel, int model, double dx, double dy, double dz) {
        Entity entity = EntityType.ITEM_DISPLAY.create(level);
        var stack = Items.ENDER_EYE.getDefaultInstance();
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model));
        CompoundTag tag = new CompoundTag();
        tag.put("item", stack.save(level.registryAccess()));
        tag.put("item_display", StringTag.valueOf("none"));
        CompoundTag transformation = new CompoundTag();
        transformation.put("left_rotation", floats(panel.lx(), panel.ly(), panel.lz(), panel.lw()));
        transformation.put("right_rotation", floats(panel.rx(), panel.ry(), panel.rz(), panel.rw()));
        transformation.put("scale", floats(panel.s1(), panel.s2(), 1f));
        transformation.put("translation", floats(0f, 0f, 0f));
        tag.put("transformation", transformation);
        tag.put("view_range", FloatTag.valueOf(4.0f));
        float box = Math.max(panel.s1(), panel.s2()) * 1.2f;
        tag.put("width", FloatTag.valueOf(box));
        tag.put("height", FloatTag.valueOf(box));
        CompoundTag brightness = new CompoundTag();
        brightness.put("sky", IntTag.valueOf(15));
        brightness.put("block", IntTag.valueOf(15));
        tag.put("brightness", brightness);   // glowing glass: not darkened at night or underground
        entity.load(tag);
        entity.setPos(centre.x + panel.x() + dx, centre.y + panel.y() + dy, centre.z + panel.z() + dz);
        return entity;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) list.add(FloatTag.valueOf(value));
        return list;
    }

    // ---- packets ----

    private static void sendAdd(ServerPlayer player, Entity entity) {
        Vec3 at = entity.position();
        player.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), at.x, at.y, at.z,
                0f, 0f, entity.getType(), 0, Vec3.ZERO, 0.0));
        var data = entity.getEntityData().getNonDefaultValues();
        if (data != null) player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), data));
    }

    private static void sendRemove(ServerPlayer player, int[] ids) {
        player.connection.send(new ClientboundRemoveEntitiesPacket(ids));
    }

    private static void expireFlashes(MinecraftServer server, long now) {
        for (Iterator<Flash> it = FLASHES.iterator(); it.hasNext();) {
            Flash flash = it.next();
            if (now < flash.expiresTick()) continue;
            remove(server, flash);
            it.remove();
        }
    }

    private static void remove(MinecraftServer server, Flash flash) {
        for (UUID id : flash.viewers()) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(id);
            if (viewer != null) sendRemove(viewer, new int[] {flash.entity().getId()});
        }
    }
}
