package com.cobbleraids.radarprobe;

import com.cobbleraids.radarclient.RadarHologramClient;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

/** Development-only end-to-end visual probe. Excluded from the shipped client JAR. */
public final class HologramGraphicalProbe implements ClientModInitializer {
    private int stage, delay, ticks;
    private Path output;
    private int terrainMin=Integer.MAX_VALUE,terrainMax=Integer.MIN_VALUE,knownSamples;
    private final boolean naturalBiome=!System.getProperty("cobbleraids.radar.smoke.biome", "").isEmpty();
    @Override public void onInitializeClient() {
        output=Path.of(System.getProperty("cobbleraids.radar.smoke.output"));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            try { tick(client); }
            catch(Throwable error) {
                try { Files.writeString(output.resolve("graphical-failure.txt"),error.toString()); } catch(Exception ignored) {}
                error.printStackTrace();client.stop();
            }
        });
    }
    private void tick(Minecraft client) throws Exception {
        if(++ticks>20*180) throw new IllegalStateException("Graphical probe timed out at stage "+stage);
        if(client.player==null || client.level==null || client.gameMode==null) return;
        client.getToasts().clear();
        var frame=RadarHologramClient.frame();
        if(frame!=null && frame.heights().length>0) {
            terrainMin=Integer.MAX_VALUE;terrainMax=Integer.MIN_VALUE;knownSamples=0;
            for(int height:frame.heights()) if(height!=Integer.MIN_VALUE) {
                terrainMin=Math.min(terrainMin,height);terrainMax=Math.max(terrainMax,height);knownSamples++;
            }
        }
        if(delay-->0) return;
        switch(stage) {
            case 0 -> {
                if(!Files.exists(output.resolve("fixture-ready"))) return;
                if(client.player.getInventory().selected!=0) {
                    client.player.getInventory().selected=0;
                    client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
                }
                if(!client.player.getMainHandItem().getHoverName().getString().equals("Raid Radar")) return;
                client.player.setYRot(naturalBiome?225:180);client.player.setXRot(15);
                client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND);
                stage=10;delay=20;
            }
            case 10 -> {
                if(!(client.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu)) throw new IllegalStateException("Raid status menu did not open");
                screenshot(client,"radar-status-menu.png");stage=11;delay=20;
            }
            case 11 -> {
                client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId,22,0,net.minecraft.world.inventory.ClickType.PICKUP,client.player);
                stage=1;delay=naturalBiome?600:240;
            }
            case 1 -> {
                if(!RadarHologramClient.matches(client.player.getMainHandItem()) || RadarHologramClient.mesh().quads().isEmpty()) return;
                Optional<?> pack=(Optional<?>)Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);
                if(pack.isEmpty() || !((Boolean)Class.forName("net.irisshaders.iris.Iris").getMethod("isPackInUseQuick").invoke(null))) {
                    throw new IllegalStateException("Iris has no active shader pack");
                }
                if(naturalBiome) {
                    if(knownSamples<30 || terrainMax-terrainMin<8) throw new IllegalStateException("Natural terrain did not produce enough varied height samples");
                    var pos=client.player.blockPosition();
                    String biome=client.level.getBiome(pos).unwrapKey().orElseThrow().location().toString();
                    long bands=java.util.Arrays.stream(RadarHologramClient.mesh().bands()).filter(b->b>=0).distinct().count();
                    if(bands<2) throw new IllegalStateException("Natural terrain mesh has no elevation variation");
                    Files.writeString(output.resolve("biome-terrain.json"),String.format(java.util.Locale.ROOT,
                        "{\"biome\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"knownSamples\":%d,\"minHeight\":%d,\"maxHeight\":%d,\"elevationBands\":%d}",
                        biome,pos.getX(),pos.getY(),pos.getZ(),knownSamples,terrainMin,terrainMax,bands));
                }
                screenshot(client,"hologram-mainhand.png");
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_H));
                stage=2;delay=30;
            }
            case 2 -> {
                if(RadarHologramClient.matches(client.player.getMainHandItem())) throw new IllegalStateException("Flat-map toggle did not disable 3D");
                screenshot(client,"hologram-flat-fallback.png");
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_H));
                stage=3;delay=20;
            }
            case 3 -> {
                client.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,BlockPos.ZERO,Direction.DOWN));
                stage=4;delay=40;
            }
            case 4 -> {
                if(!RadarHologramClient.matches(client.player.getOffhandItem())) throw new IllegalStateException("Offhand radar did not match live data");
                screenshot(client,"hologram-offhand.png");
                stage=5;delay=30;
            }
            case 5 -> {
                for(String name:new String[]{"hologram-mainhand.png","hologram-flat-fallback.png","hologram-offhand.png"}) {
                    if(!Files.exists(output.resolve("screenshots").resolve(name))) return;
                }
                Files.writeString(output.resolve("graphical-result.json"),"{\"passed\":true,\"shader\":\"ComplementaryUnbound_r5.1.1\",\"checks\":[\"mainhand real geometry\",\"H flat fallback\",\"offhand real geometry\"]}");
                stage=6;client.stop();
            }
        }
    }
    private void screenshot(Minecraft client,String name) {
        Screenshot.grab(output.toFile(),name,client.getMainRenderTarget(),message->System.out.println("RADAR SCREENSHOT: "+message.getString()));
    }
}
