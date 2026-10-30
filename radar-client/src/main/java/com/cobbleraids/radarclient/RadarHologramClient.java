package com.cobbleraids.radarclient;

import com.cobbleraids.radar.protocol.RadarHologramPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public final class RadarHologramClient implements ClientModInitializer {
    private static RadarHologramPayload frame;
    private static HologramMesh.Mesh mesh;
    private static boolean enabled = true;
    private static long openedAt;
    @Override public void onInitializeClient() {
        WorldRenderEvents.AFTER_ENTITIES.register(HologramProjectionRenderer::render);
        PayloadTypeRegistry.playS2C().register(RadarHologramPayload.TYPE, RadarHologramPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(RadarHologramPayload.TYPE,
                (payload, context) -> context.client().execute(() -> accept(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        KeyMapping toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.cobbleraids_radar.toggle",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.cobbleraids_radar"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggle.consumeClick()) {
                enabled = !enabled;
                if (client.player != null) client.player.displayClientMessage(
                        Component.literal(enabled ? "Raid Radar: 3D hologram" : "Raid Radar: flat map"), true);
            }
        });
    }
    private static void accept(RadarHologramPayload payload) {
        boolean newView = frame == null || frame.mapId() != payload.mapId()
                || frame.centerX() != payload.centerX() || frame.centerZ() != payload.centerZ();
        if (newView && payload.heights().length == 0) { clear(); return; }
        boolean firstTerrain = mesh == null || mesh.quads().isEmpty();
        if (payload.heights().length != 0) mesh = HologramMesh.build(payload.heights());
        if (newView || (firstTerrain && mesh != null && !mesh.quads().isEmpty())) openedAt = System.nanoTime();
        frame = payload;
    }
    public static boolean matches(ItemStack item) {
        var id = item.get(DataComponents.MAP_ID);
        return enabled && frame != null && mesh != null && item.is(Items.FILLED_MAP)
                && id != null && id.id() == frame.mapId();
    }
    public static RadarHologramPayload frame() { return frame; }
    public static HologramMesh.Mesh mesh() { return mesh; }
    public static float emergence() { return Math.clamp((System.nanoTime() - openedAt) / 300_000_000f, 0, 1); }
    private static void clear() { frame = null; mesh = null; openedAt = 0; }
}
