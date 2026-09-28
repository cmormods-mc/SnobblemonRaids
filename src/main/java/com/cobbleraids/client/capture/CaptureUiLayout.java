package com.cobbleraids.client.capture;

/**
 * Pure layout math shared by the offer, minigame and result screens: one centered panel with a
 * header, an info column, a chamber, and (outside {@code contentHeight}) a timing track and action
 * button -- the same "pure record computes positions, the screen only renders against them" split
 * {@code TrophyGalleryLayout} uses. Sized as a plain responsive panel rather than measured against a
 * texture's native canvas, because the Raid Capture Protocol's art is entirely drawn (see
 * {@link CaptureArt}), not blitted from a fixed-resolution file.
 *
 * <p>Secondary decoration ({@code CaptureArt.scanner}, the per-pulse quality rows, the lamp strip)
 * checks {@link #contentHeight()}/{@link #width()} against its own thresholds and hides itself at
 * compact GUI scales rather than overflowing -- see {@code CaptureUiLayoutTest} for the sweep across
 * scales this depends on staying true.
 */
public record CaptureUiLayout(int x, int y, int width, int height) {
    private static final int MAX_WIDTH = 700;
    private static final int MAX_HEIGHT = 420;
    private static final int SCREEN_MARGIN = 16;

    public static CaptureUiLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(0, Math.min(MAX_WIDTH, screenWidth - SCREEN_MARGIN));
        int height = Math.max(0, Math.min(MAX_HEIGHT, screenHeight - SCREEN_MARGIN));
        return new CaptureUiLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    /** Below this, the layout is still drawn (nothing throws), but content is expected to be cramped. */
    public boolean usable() {
        return width >= 280 && height >= 210;
    }

    public int headerY() { return y + 14; }
    public int contentY() { return y + 39; }
    public int contentHeight() { return height - 146; }

    public int infoX() { return x + 12; }
    public int infoWidth() { return Math.max(92, width / 3); }
    public int chamberX() { return infoX() + infoWidth() + 9; }
    public int chamberWidth() { return x + width - 12 - chamberX(); }

    public int trackX() { return x + 18; }
    public int trackY() { return y + height - 81; }
    public int trackWidth() { return width - 36; }
    public int buttonY() { return y + height - 38; }

    /** The moving indicator's x position for {@code progress} in {@code 0..1} across the track. */
    public int indicator(double progress) {
        return trackX() + (int) Math.round(Math.clamp(progress, 0, 1) * trackWidth());
    }

    /**
     * A timing zone's width in track pixels, sized in proportion to how much of the whole travel
     * duration that zone covers -- the same relationship the server itself judges against, just
     * drawn instead of computed from elapsed millis.
     */
    public int zoneWidth(int zoneWidthMs, int travelDurationMs) {
        double fraction = travelDurationMs <= 0 ? 0.0 : Math.clamp(zoneWidthMs / (double) travelDurationMs, 0, 1);
        return Math.max(1, (int) Math.round(trackWidth() * fraction));
    }

    /**
     * The indicator sweeps end-to-end every {@code travelDurationMs}, then bounces back, for as long
     * as the player takes to press -- it never simply stops at the far side with nothing left to do.
     * The server scores a press the same way: against whichever crossing of the track's fixed center
     * it actually landed nearest, not against ever-growing absolute elapsed time, so this animation
     * and the score it produces stay consistent no matter how many passes the player lets go by.
     */
    public static double bounce(long elapsedMs, int travelDurationMs) {
        if (travelDurationMs <= 0) return 1.0;
        long cyclePos = Math.floorMod(elapsedMs, 2L * travelDurationMs);
        return cyclePos <= travelDurationMs
                ? cyclePos / (double) travelDurationMs
                : (2.0 * travelDurationMs - cyclePos) / travelDurationMs;
    }
}
