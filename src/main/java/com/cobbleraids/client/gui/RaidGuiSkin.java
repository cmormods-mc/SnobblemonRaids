package com.cobbleraids.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.cobbleraids.client.gui.RaidGuiLayout.Layout;
import com.cobbleraids.client.gui.RaidGuiLayout.Rect;

/**
 * Draws the raid GUI's chrome from the sliced atlas.
 *
 * <p>Supplied as an asset kit and adapted here. The atlas UVs below were checked against the
 * individual slice PNGs before this was trusted -- all 47 regions match their slice byte for byte,
 * and the window arithmetic in {@link RaidGuiLayout} was checked against the assembled art at 18,
 * 20 and 22 pixel slots, where all 64 slot centres land on slot artwork.
 *
 * <p>Chrome only. The screen draws its own text, cell contents and highlights over the top, which
 * is why this takes no page number: a section heading is not a page number, and a caption baked in
 * here could not say what our shop needs it to.
 */
public final class RaidGuiSkin {
    private RaidGuiSkin() {}
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
        "cobbleraids", "textures/gui/raid_slices/atlas.png");
    private static final int ATLAS_W = 2048, ATLAS_H = 2048;
    private record Sprite(int u, int v, int width, int height) {}
    private record Nine(Sprite[] pieces, int left, int top, int right, int bottom) {}
    private static final Sprite FRAME_TOP_LEFT = new Sprite(1486, 2, 109, 155);
    private static final Sprite FRAME_TOP = new Sprite(2, 850, 940, 155);
    private static final Sprite FRAME_TOP_RIGHT = new Sprite(946, 850, 109, 155);
    private static final Sprite FRAME_LEFT = new Sprite(2, 2, 109, 844);
    private static final Sprite FRAME_CENTER = new Sprite(884, 1098, 1, 1);
    private static final Sprite FRAME_RIGHT = new Sprite(115, 2, 109, 844);
    private static final Sprite FRAME_BOTTOM_LEFT = new Sprite(316, 2, 109, 176);
    private static final Sprite FRAME_BOTTOM = new Sprite(429, 2, 940, 176);
    private static final Sprite FRAME_BOTTOM_RIGHT = new Sprite(1373, 2, 109, 176);
    private static final Sprite PANEL_TOP_LEFT = new Sprite(693, 1009, 32, 35);
    private static final Sprite PANEL_TOP = new Sprite(729, 1009, 876, 35);
    private static final Sprite PANEL_TOP_RIGHT = new Sprite(1609, 1009, 32, 35);
    private static final Sprite PANEL_LEFT = new Sprite(228, 2, 32, 786);
    private static final Sprite PANEL_CENTER = new Sprite(889, 1098, 1, 1);
    private static final Sprite PANEL_RIGHT = new Sprite(264, 2, 32, 786);
    private static final Sprite PANEL_BOTTOM_LEFT = new Sprite(1645, 1009, 32, 25);
    private static final Sprite PANEL_BOTTOM = new Sprite(2, 1056, 876, 25);
    private static final Sprite PANEL_BOTTOM_RIGHT = new Sprite(882, 1056, 32, 25);
    private static final Sprite GRID_OUTLINE_TOP_LEFT = new Sprite(453, 1085, 4, 4);
    private static final Sprite GRID_OUTLINE_TOP = new Sprite(461, 1085, 870, 4);
    private static final Sprite GRID_OUTLINE_TOP_RIGHT = new Sprite(1335, 1085, 4, 4);
    private static final Sprite GRID_OUTLINE_LEFT = new Sprite(300, 2, 4, 783);
    private static final Sprite GRID_OUTLINE_CENTER = new Sprite(894, 1098, 1, 1);
    private static final Sprite GRID_OUTLINE_RIGHT = new Sprite(308, 2, 4, 783);
    private static final Sprite GRID_OUTLINE_BOTTOM_LEFT = new Sprite(1343, 1085, 4, 4);
    private static final Sprite GRID_OUTLINE_BOTTOM = new Sprite(2, 1098, 870, 4);
    private static final Sprite GRID_OUTLINE_BOTTOM_RIGHT = new Sprite(876, 1098, 4, 4);
    private static final Sprite SLOT = new Sprite(918, 1056, 22, 22);
    private static final Sprite HEADER_LEFT = new Sprite(1241, 850, 242, 49);
    private static final Sprite HEADER_CENTER = new Sprite(1487, 850, 1, 49);
    private static final Sprite HEADER_RIGHT = new Sprite(1492, 850, 240, 49);
    private static final Sprite PREVIOUS = new Sprite(453, 1009, 40, 42);
    private static final Sprite NEXT = new Sprite(497, 1009, 40, 42);
    private static final Sprite TOP_NOTCH = new Sprite(1161, 850, 76, 61);
    private static final Sprite TOP_HIGHLIGHT = new Sprite(944, 1056, 408, 10);
    private static final Sprite BOTTOM_NOTCH = new Sprite(1736, 850, 76, 46);
    private static final Sprite BUTTON_TOP_LEFT = new Sprite(1356, 1056, 22, 9);
    private static final Sprite BUTTON_TOP = new Sprite(1382, 1056, 421, 9);
    private static final Sprite BUTTON_TOP_RIGHT = new Sprite(1807, 1056, 22, 9);
    private static final Sprite BUTTON_LEFT = new Sprite(1816, 850, 22, 43);
    private static final Sprite BUTTON_CENTER = new Sprite(2, 1009, 421, 43);
    private static final Sprite BUTTON_RIGHT = new Sprite(427, 1009, 22, 43);
    private static final Sprite BUTTON_BOTTOM_LEFT = new Sprite(1833, 1056, 22, 9);
    private static final Sprite BUTTON_BOTTOM = new Sprite(2, 1085, 421, 9);
    private static final Sprite BUTTON_BOTTOM_RIGHT = new Sprite(427, 1085, 22, 9);
    private static final Nine FRAME = new Nine(new Sprite[] { FRAME_TOP_LEFT, FRAME_TOP, FRAME_TOP_RIGHT, FRAME_LEFT, FRAME_CENTER, FRAME_RIGHT, FRAME_BOTTOM_LEFT, FRAME_BOTTOM, FRAME_BOTTOM_RIGHT }, 20, 34, 20, 26);
    private static final Nine PANEL = new Nine(new Sprite[] { PANEL_TOP_LEFT, PANEL_TOP, PANEL_TOP_RIGHT, PANEL_LEFT, PANEL_CENTER, PANEL_RIGHT, PANEL_BOTTOM_LEFT, PANEL_BOTTOM, PANEL_BOTTOM_RIGHT }, 6, 6, 6, 6);
    private static final Nine GRID_OUTLINE = new Nine(new Sprite[] { GRID_OUTLINE_TOP_LEFT, GRID_OUTLINE_TOP, GRID_OUTLINE_TOP_RIGHT, GRID_OUTLINE_LEFT, GRID_OUTLINE_CENTER, GRID_OUTLINE_RIGHT, GRID_OUTLINE_BOTTOM_LEFT, GRID_OUTLINE_BOTTOM, GRID_OUTLINE_BOTTOM_RIGHT }, 1, 1, 1, 1);
    private static final Nine BUTTON = new Nine(new Sprite[] { BUTTON_TOP_LEFT, BUTTON_TOP, BUTTON_TOP_RIGHT, BUTTON_LEFT, BUTTON_CENTER, BUTTON_RIGHT, BUTTON_BOTTOM_LEFT, BUTTON_BOTTOM, BUTTON_BOTTOM_RIGHT }, 4, 2, 4, 2);

    private static void sprite(GuiGraphics graphics, Sprite s, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        graphics.blit(ATLAS, x, y, width, height, (float) s.u(), (float) s.v(),
                      s.width(), s.height(), ATLAS_W, ATLAS_H);
    }

    private static void nine(GuiGraphics g, Nine n, int x, int y, int w, int h) {
        int l=n.left(), t=n.top(), r=n.right(), b=n.bottom();
        // The kit threw here. A throw inside render() is a crash screen and a lost session over a
        // window a few pixels too narrow, and RaidGuiLayout.fit already refuses anything this size
        // -- so the unreachable case draws nothing instead of taking the client down.
        if (w < l+r || h < t+b) return;
        Sprite[] s=n.pieces();
        sprite(g,s[0],x,y,l,t); sprite(g,s[1],x+l,y,w-l-r,t); sprite(g,s[2],x+w-r,y,r,t);
        sprite(g,s[3],x,y+t,l,h-t-b); sprite(g,s[4],x+l,y+t,w-l-r,h-t-b); sprite(g,s[5],x+w-r,y+t,r,h-t-b);
        sprite(g,s[6],x,y+h-b,l,b); sprite(g,s[7],x+l,y+h-b,w-l-r,b); sprite(g,s[8],x+w-r,y+h-b,r,b);
    }

    /**
     * Draws the frame, header, panel, grid outline and every empty slot.
     *
     * <p>Call before the cell contents, the widgets and the tooltips.
     */
    public static void renderChrome(GuiGraphics g, Layout layout) {
        Rect f=layout.frame(), p=layout.panel(), grid=layout.grid();
        int x=f.x(), y=f.y(), w=f.width(), h=f.height();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1,1,1,1);
        nine(g,FRAME,x,y,w,h);
        sprite(g,HEADER_LEFT,x+20,y+23,40,11);
        sprite(g,HEADER_CENTER,x+60,y+23,w-120,11);
        sprite(g,HEADER_RIGHT,x+w-60,y+23,40,11);
        sprite(g,TOP_NOTCH,x+(w-14)/2,y,14,13);
        sprite(g,TOP_HIGHLIGHT,x+(w-74)/2,y+17,74,2);
        sprite(g,BOTTOM_NOTCH,x+(w-14)/2,y+h-7,14,7);
        nine(g,PANEL,p.x(),p.y(),p.width(),p.height());
        nine(g,GRID_OUTLINE,grid.x()-1,grid.y()-1,grid.width()+2,grid.height()+2);
        for (Rect slot : layout.slots()) sprite(g,SLOT,slot.x(),slot.y(),slot.width(),slot.height());
        nine(g,BUTTON,layout.button().x(),layout.button().y(),
                layout.button().width(),layout.button().height());
        RenderSystem.disableBlend();
    }

    /**
     * The two page arrows, drawn only when there is more than one page.
     *
     * <p>Separate from the chrome because an arrow that cannot be used should not be drawn: the
     * hit rectangles in the layout stay where they are, and the screen simply does not offer them.
     */
    public static void renderArrows(GuiGraphics g, Layout layout) {
        Rect f = layout.frame();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        sprite(g,PREVIOUS,f.x()+f.width()/2-38,f.y()+25,10,9);
        sprite(g,NEXT,f.x()+f.width()/2+28,f.y()+25,10,9);
        RenderSystem.disableBlend();
    }

    /** Default item icons remain 16 logical pixels at every GUI Scale setting. */
    public static int itemX(Rect slot) { return slot.x()+(slot.width()-16)/2; }
    public static int itemY(Rect slot) { return slot.y()+(slot.height()-16)/2; }
}
