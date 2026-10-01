package com.cobbleraids.client.capture;

import com.cobbleraids.client.shop.CapturePokemonPreview;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Shared chrome and text helpers for the Raid Capture Protocol's screens. Package-private by design. */
final class CaptureUi {
    static final int CYAN = 0xFF37EEFF;
    static final int WHITE = 0xFFEAFBFF;
    static final int NAVY = 0xFF03283F;
    static final int MUTED = 0xFF9ABACB;

    private CaptureUi() {}

    /** A filled panel with a one-pixel dark border and a colored edge inside it. */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x, y, x + w, y + h, 0xFF001425);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, edge);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, fill);
    }

    /** The outer frame, header bar with heading text, footer circuitry, and the two content panels. */
    static void frame(GuiGraphics g, Font font, CaptureUiLayout layout, String heading) {
        panel(g, layout.x(), layout.y(), layout.width(), layout.height(), 0xFF064578, 0xFF008FFF);
        panel(g, layout.x() + 6, layout.y() + 6, layout.width() - 12, 26, NAVY, CYAN);
        int headingWidth = layout.width() >= 460 ? layout.width() - 220 : layout.width() - 34;
        text(g, font, heading, layout.x() + 17, layout.headerY(), headingWidth, WHITE);
        CaptureArt.drawFooter(g, layout);
        panel(g, layout.infoX(), layout.contentY(), layout.infoWidth(), layout.contentHeight(), NAVY, CYAN);
        panel(g, layout.chamberX(), layout.contentY(), layout.chamberWidth(), layout.contentHeight(), NAVY, CYAN);
    }

    /**
     * The hologram chamber: the boss's own sprite while idle, a bouncing Poke Ball silhouette while a
     * throw is in flight or the result is still being revealed, or nothing once it has escaped.
     * Scissored to its own panel since the chamber's perspective walls are drawn edge-to-edge.
     */
    static void chamber(GuiGraphics g, Font font, CaptureUiLayout layout, CaptureOfferPayload offer,
                         CaptureDetailsPayload details, float partialTick, boolean ball, long animationMs,
                         boolean empty) {
        int x = layout.chamberX() + 5, y = layout.contentY() + 5;
        int w = layout.chamberWidth() - 10, h = layout.contentHeight() - 10;
        g.enableScissor(x, y, x + w, y + h);
        try {
            CaptureArt.drawChamber(g, x, y, w, h);
            if (ball) {
                int size = Math.max(14, Math.min(30, h / 3));
                int shift = (int) (Math.sin(animationMs / 85.0) * 3);
                pokeball(g, x + w / 2 - size / 2 + shift, y + h / 2 - size / 2, size);
            } else if (!empty && details != null) {
                int size = Math.max(16, Math.min(w * 2 / 3, h * 3 / 4));
                boolean drew = CapturePokemonPreview.draw(g, details.species(), offer.shiny(),
                        x + (w - size) / 2, y + (h - size) / 2, size, partialTick);
                if (!drew) centered(g, font, "?", x, y + h / 2, w, WHITE);
            } else if (!empty) {
                centered(g, font, offer.speciesDisplayName(), x + 8, y + h / 2, w - 16, WHITE);
            }
        } finally {
            g.disableScissor();
        }
    }

    /** A flat-shaded Poke Ball silhouette, drawn rather than blitted so it scales to any chamber size. */
    static void pokeball(GuiGraphics g, int x, int y, int s) {
        g.fill(x + 3, y, x + s - 3, y + s, 0xFF001425);
        g.fill(x, y + 3, x + s, y + s - 3, 0xFF001425);
        g.fill(x + 3, y + 3, x + s - 3, y + s / 2, 0xFFFF4F59);
        g.fill(x + 3, y + s / 2, x + s - 3, y + s - 3, WHITE);
        g.fill(x, y + s / 2 - 2, x + s, y + s / 2 + 2, 0xFF001425);
        g.fill(x + s / 2 - 4, y + s / 2 - 4, x + s / 2 + 4, y + s / 2 + 4, 0xFF001425);
        g.fill(x + s / 2 - 2, y + s / 2 - 2, x + s / 2 + 2, y + s / 2 + 2, WHITE);
    }

    static void text(GuiGraphics g, Font font, String s, int x, int y, int width, int color) {
        g.drawString(font, fit(font, s, width), x, y, color, false);
    }

    static String fit(Font font, String s, int width) {
        return font.width(s) <= width ? s : font.plainSubstrByWidth(s, Math.max(0, width - 9)) + "...";
    }

    static void centered(GuiGraphics g, Font font, String s, int x, int y, int width, int color) {
        String fitted = fit(font, s, width);
        g.drawString(font, fitted, x + (width - font.width(fitted)) / 2, y, color, false);
    }

    /** Word-wraps {@code s} to {@code width}, one line per 10px, and returns the y just past the last line. */
    static int wrap(GuiGraphics g, Font font, String s, int x, int y, int width, int color) {
        for (var line : font.split(Component.literal(s), width)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    /** A flat-panel button matching this UI's chrome instead of vanilla's beveled button texture. */
    static final class Action extends AbstractButton {
        private final Font font;
        private final Runnable onPress;

        Action(Font font, int x, int y, int w, String text, Runnable onPress) {
            super(x, y, w, 24, Component.literal(text));
            this.font = font;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int fill = active && isHoveredOrFocused() ? 0xFF0987B8 : NAVY;
            int edge = active ? CYAN : 0xFF537E92;
            panel(g, getX(), getY(), getWidth(), getHeight(), fill, edge);
            centered(g, font, getMessage().getString(), getX() + 5, getY() + 8, getWidth() - 10,
                    active ? WHITE : MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
