package com.cobbleraids.client.capture;

/**
 * Pure layout math for the capture minigame screen: a centered panel holding one horizontal track,
 * the same "pure record computes positions, the screen only renders against them" split
 * {@code TrophyGalleryLayout} uses. No bespoke art exists for this feature yet (see
 * {@link CaptureOfferScreen}'s own note), so this is sized as a plain panel rather than measured
 * against a texture's native canvas -- replace {@link #fit} wholesale if real art arrives later, the
 * same way {@code TrophyGalleryLayout} would be re-measured against a new file.
 */
public record CaptureMinigameLayout(int panelX, int panelY, int panelWidth, int panelHeight,
                                     int trackX, int trackY, int trackWidth, int trackHeight) {

    private static final int MAX_PANEL_WIDTH = 360;
    private static final int MAX_PANEL_HEIGHT = 160;
    private static final int SCREEN_MARGIN = 32;
    private static final int TRACK_SIDE_MARGIN = 20;
    private static final int TRACK_HEIGHT = 24;
    private static final int TRACK_BOTTOM_MARGIN = 46;

    public static CaptureMinigameLayout fit(int screenWidth, int screenHeight) {
        int panelWidth = Math.max(200, Math.min(MAX_PANEL_WIDTH, screenWidth - SCREEN_MARGIN));
        int panelHeight = Math.max(120, Math.min(MAX_PANEL_HEIGHT, screenHeight - SCREEN_MARGIN));
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;
        int trackX = panelX + TRACK_SIDE_MARGIN;
        int trackWidth = panelWidth - TRACK_SIDE_MARGIN * 2;
        int trackY = panelY + panelHeight - TRACK_BOTTOM_MARGIN;
        return new CaptureMinigameLayout(panelX, panelY, panelWidth, panelHeight,
                trackX, trackY, trackWidth, TRACK_HEIGHT);
    }

    /** The moving indicator's x position for {@code progress} in {@code 0..1} across the track. */
    public int indicatorX(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return trackX + (int) Math.round(clamped * trackWidth);
    }

    public int trackCenterX() {
        return trackX + trackWidth / 2;
    }

    /**
     * The screen-space {@code [x, width]} of a timing zone centered on the track, sized in proportion
     * to how much of the whole travel duration that zone covers -- the same relationship the server
     * itself judges against, just drawn instead of computed from elapsed millis.
     */
    public int[] zoneBounds(int zoneWidthMs, int travelDurationMs) {
        double fraction = travelDurationMs <= 0 ? 0.0 : Math.min(1.0, zoneWidthMs / (double) travelDurationMs);
        int width = Math.max(1, (int) Math.round(trackWidth * fraction));
        int x = trackCenterX() - width / 2;
        return new int[] {x, width};
    }

    public int titleY() {
        return panelY + 14;
    }

    public int subtitleY() {
        return panelY + 30;
    }

    public int buttonY() {
        return trackY - 30;
    }
}
