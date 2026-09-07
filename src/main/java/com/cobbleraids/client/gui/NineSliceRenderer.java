package com.cobbleraids.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a texture as a nine-slice panel: fixed corners, edges tiled/stretched along one axis, a
 * stretched center. Keeps border pixel art crisp at any panel size instead of distorting it the way
 * a single stretched blit would. Not wired into any screen yet -- there is no bundled texture to
 * slice until real art exists; this is the ready-to-use utility for when one does.
 */
public final class NineSliceRenderer {
    private NineSliceRenderer() {}

    public static void draw(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x,
            int y,
            int width,
            int height,
            int textureWidth,
            int textureHeight,
            int border
    ) {
        if (width < border * 2 || height < border * 2) return;

        int middleSourceWidth = textureWidth - border * 2;
        int middleSourceHeight = textureHeight - border * 2;
        int middleWidth = width - border * 2;
        int middleHeight = height - border * 2;

        // Center
        drawRegion(graphics, texture, x + border, y + border, border, border,
                middleWidth, middleHeight, middleSourceWidth, middleSourceHeight, textureWidth, textureHeight);

        // Top and bottom edges
        drawRegion(graphics, texture, x + border, y, border, 0,
                middleWidth, border, middleSourceWidth, border, textureWidth, textureHeight);
        drawRegion(graphics, texture, x + border, y + height - border, border, textureHeight - border,
                middleWidth, border, middleSourceWidth, border, textureWidth, textureHeight);

        // Left and right edges
        drawRegion(graphics, texture, x, y + border, 0, border,
                border, middleHeight, border, middleSourceHeight, textureWidth, textureHeight);
        drawRegion(graphics, texture, x + width - border, y + border, textureWidth - border, border,
                border, middleHeight, border, middleSourceHeight, textureWidth, textureHeight);

        // Corners
        drawRegion(graphics, texture, x, y, 0, 0,
                border, border, border, border, textureWidth, textureHeight);
        drawRegion(graphics, texture, x + width - border, y, textureWidth - border, 0,
                border, border, border, border, textureWidth, textureHeight);
        drawRegion(graphics, texture, x, y + height - border, 0, textureHeight - border,
                border, border, border, border, textureWidth, textureHeight);
        drawRegion(graphics, texture, x + width - border, y + height - border, textureWidth - border, textureHeight - border,
                border, border, border, border, textureWidth, textureHeight);
    }

    private static void drawRegion(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x,
            int y,
            int sourceX,
            int sourceY,
            int destinationWidth,
            int destinationHeight,
            int sourceWidth,
            int sourceHeight,
            int textureWidth,
            int textureHeight
    ) {
        // blit(location, x, y, width, height, uOffset, vOffset, uWidth, vHeight, texWidth, texHeight):
        // width/height is the destination size, uWidth/vHeight is the sampled source region -- they
        // may differ, which is exactly what lets the stretched center/edges scale independently of
        // the fixed-size corners.
        graphics.blit(texture, x, y, destinationWidth, destinationHeight,
                sourceX, sourceY, sourceWidth, sourceHeight, textureWidth, textureHeight);
    }
}
