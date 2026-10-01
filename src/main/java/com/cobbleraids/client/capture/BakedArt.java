package com.cobbleraids.client.capture;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * One piece of the capture screens' artwork that never changes between frames, painted once into a
 * texture and drawn with a single blit instead of re-issuing thousands of one-pixel fills every
 * frame. One texel is one GUI pixel, so at any GUI scale the result is the same hard-edged pixel
 * grid the live fills produced.
 *
 * <p>Must be used from the render thread. {@link #release()} must run when the screen closes, or the
 * texture outlives it.
 */
final class BakedArt {
    private static final AtomicInteger IDS = new AtomicInteger();

    private final String name;
    private ResourceLocation id;
    private DynamicTexture texture;
    private int width, height, key;

    BakedArt(String name) {
        this.name = name;
    }

    /**
     * Draws the art at {@code x, y}, painting it first if it has not been painted at this size.
     *
     * @param key anything besides the size that changes what is painted
     */
    void draw(GuiGraphics g, int x, int y, int w, int h, int key, Consumer<CaptureArt.Pen> painter) {
        if (w <= 0 || h <= 0) return;
        if (texture == null || w != width || h != height || key != this.key) {
            release();
            bake(w, h, painter);
            this.key = key;
        }
        g.blit(id, x, y, 0, 0, w, h, w, h);
    }

    private void bake(int w, int h, Consumer<CaptureArt.Pen> painter) {
        NativeImage image = new NativeImage(w, h, true);
        painter.accept((x1, y1, x2, y2, color) -> {
            int ax = Math.max(0, Math.min(x1, x2)), bx = Math.min(w, Math.max(x1, x2));
            int ay = Math.max(0, Math.min(y1, y2)), by = Math.min(h, Math.max(y1, y2));
            if (bx > ax && by > ay) image.fillRect(ax, ay, bx - ax, by - ay, toAbgr(color));
        });
        texture = new DynamicTexture(image);
        id = ResourceLocation.fromNamespaceAndPath("cobbleraids", "capture_art/" + name + "_" + IDS.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(id, texture);
        width = w;
        height = h;
    }

    /** NativeImage stores pixels as ABGR; the art's colors are written as ARGB. */
    static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    void release() {
        if (texture == null) return;
        Minecraft.getInstance().getTextureManager().release(id);
        texture = null;
        id = null;
    }
}
