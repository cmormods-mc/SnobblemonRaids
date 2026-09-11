package com.cobbleraids.client.reveal;

/**
 * Where the granted-item lines go inside the reveal screen's chamber, and how big they are.
 *
 * <p>Separated from the screen, and free of Minecraft types, because it is arithmetic that was
 * wrong and could not be tested where it lived. The chamber is art that scales with the window
 * while the line step was a hard 12 pixels, so the taller the bundle and the smaller the window,
 * the further the text ran past the bottom of the panel it is supposed to sit in. At the minimum
 * 360px panel width only three lines fit; a six-selection claim with a currency line is seven.
 *
 * <p>Two moves, in order. A long list starts higher -- up to the chamber's vertical centre, never
 * above it, so a one or two line result stays exactly where it has always been. If it still does
 * not fit, the whole block scales down until it does. The result never leaves the chamber.
 */
record ResultTextLayout(int top, float scale, int step) {

    /** The design line height, at full scale. Matches the vanilla font's 9px plus leading. */
    static final int LINE_STEP = 12;

    /** Below this the text is unreadable, so the block is allowed to clip rather than vanish. */
    private static final float MIN_SCALE = 0.5f;

    /**
     * @param chamberY      the chamber's top edge in screen space
     * @param chamberHeight the chamber's height in screen space
     * @param chamberWidth  the chamber's width, which sets the preferred gap below centre
     * @param lineCount     how many lines are to be drawn
     */
    static ResultTextLayout of(int chamberY, int chamberHeight, int chamberWidth, int lineCount) {
        int centre = chamberY + chamberHeight / 2;
        int bottom = chamberY + chamberHeight;
        int preferred = centre + Math.round(chamberWidth * 0.1f);
        if (lineCount <= 0) return new ResultTextLayout(preferred, 1.0f, LINE_STEP);

        int needed = lineCount * LINE_STEP;
        // Slide up towards the centre, but no further: above it the text would cross the art the
        // chamber exists to frame.
        int top = Math.max(centre, Math.min(preferred, bottom - needed));
        int room = bottom - top;
        float scale = room >= needed ? 1.0f : Math.max(MIN_SCALE, room / (float) needed);
        return new ResultTextLayout(top, scale, LINE_STEP);
    }

    /** True when the block is drawn at its design size, which is the common case. */
    boolean unscaled() {
        return scale >= 1.0f;
    }

    /** Total height the block will occupy on screen. */
    int height(int lineCount) {
        return Math.round(lineCount * step * scale);
    }
}
