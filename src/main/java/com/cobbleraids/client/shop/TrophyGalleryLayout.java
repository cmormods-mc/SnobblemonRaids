package com.cobbleraids.client.shop;

/**
 * The trophy room's own layout, independent of {@link com.cobbleraids.client.gui.RaidGuiLayout}'s
 * sliced-art grid: a gallery of up to three wide display cases reads better as a bespoke panel than
 * as more cells forced into the shop's fixed slot size.
 *
 * <p>Everything here is in Minecraft's logical GUI pixels, the same convention {@code RaidGuiLayout}
 * documents: the game applies the user's GUI Scale once, after this layout runs, so nothing here
 * multiplies a coordinate by that scale itself.
 *
 * <p>Every constant below is a pixel measured directly against {@code gallery.png}'s own native
 * {@link #TEXTURE_WIDTH}x{@link #TEXTURE_HEIGHT} canvas -- the art bakes in the frame, the three
 * display-case alcoves (hand-painted, not evenly spaced: the centre one sits between two structural
 * dividers and is narrower than the outer two), the header/footer button plates and the thumbnail
 * slots, and every position here exists to put a real widget or a piece of text exactly on top of
 * the shape already painted there. {@link #px}/{@link #py}/{@link #dx}/{@link #dy} convert one of
 * those texture-space measurements into the screen-space this layout is actually drawn at; nothing
 * here should be adjusted by feel; if the art changes, re-measure the new file the same way this one
 * was and replace these numbers wholesale.
 *
 * @param columns    how many display cases fit side by side: 3, 2 or 1, chosen from width alone so a
 *                   resize always lands on one of three known-good layouts rather than an
 *                   interpolated one nothing has been tested against. Only the 3-column layout uses
 *                   the art's own hand-painted case positions; 2 and 1 fall back to an evenly-spaced
 *                   {@link #cardX}/{@link #cardWidth}, cropping a single case's art per slot instead
 *                   (see {@code TrophyRoomScreen#drawFrame}).
 * @param thumbnails whether there is enough vertical room for the preview strip below the cases.
 */
public record TrophyGalleryLayout(int x, int y, int width, int height, int columns, boolean thumbnails) {

    /** The art's own canvas size; every measurement below is a pixel position on this canvas. */
    public static final int TEXTURE_WIDTH = 1672;
    public static final int TEXTURE_HEIGHT = 941;

    /**
     * The panel never grows past this even on a very large window. Chosen to preserve
     * {@code TEXTURE_WIDTH}:{@code TEXTURE_HEIGHT} exactly (900/1672 == 507/941), so the art is never
     * the reason to stretch it off its own aspect ratio -- {@link #fit} still re-derives one dimension
     * from the other for whichever axis is tighter on an unusually shaped window.
     */
    private static final int MAX_WIDTH = 900;
    private static final int MAX_HEIGHT = 507;
    /** Clearance kept from the edge of the screen on every side. */
    private static final int SCREEN_MARGIN = 24;
    /** Below this the smallest 2-case layout would clip its own cards; see {@link #usable()}. */
    private static final int MIN_USABLE_WIDTH = 220;
    private static final int MIN_USABLE_HEIGHT = 200;
    private static final int THREE_COLUMN_WIDTH = 500;
    private static final int TWO_COLUMN_WIDTH = 340;
    /** Below this the third heading label ("COBBLEMON RAID NETWORK") would collide with the others. */
    private static final int WIDE_HEADING_WIDTH = 760;
    /** Below this, the thumbnail strip is dropped rather than drawn cramped under the cases. */
    private static final int THUMBNAIL_MIN_HEIGHT = 280;

    // ---------------------------------------------------------------- header row (search + filters)
    private static final int HEADER_Y = 134;
    private static final int HEADER_HEIGHT = 52;
    private static final int SEARCH_X = 137;
    private static final int SEARCH_WIDTH = 430;
    private static final int TIER_BUTTON_X = 583;
    private static final int TIER_BUTTON_WIDTH = 308;
    private static final int SHINY_BUTTON_X = 906;
    private static final int SHINY_BUTTON_WIDTH = 309;
    private static final int SORT_BUTTON_X = 1230;
    private static final int SORT_BUTTON_WIDTH = 307;

    // ---------------------------------------------------------------- heading text
    private static final int TITLE_X = 209;
    private static final int TITLE_Y = 89;
    private static final int TITLE_WIDTH = 320;
    private static final int SUBTITLE_X = 680;
    private static final int SUBTITLE_Y = 92;
    private static final int SUBTITLE_WIDTH = 320;
    private static final int BRAND_X = 1170;
    private static final int BRAND_Y = 94;
    private static final int BRAND_WIDTH = 315;

    // ---------------------------------------------------------------- the three display cases
    /** Hand-painted, not evenly spaced -- see the class javadoc. Indexed 0..2, left to right. */
    private static final int[] CASE_X = {134, 615, 1070};
    private static final int[] CASE_WIDTH = {469, 444, 469};
    private static final int CASE_TOP = 198;
    /** Where the case art ends with the thumbnail strip showing versus not (it grows to fill the gap). */
    private static final int CASE_BOTTOM_WITH_THUMBNAILS = 699;
    private static final int CASE_BOTTOM_WITHOUT_THUMBNAILS = 820;
    /** The crop used as a single case's art at the 1- and 2-column fallback layouts (case 0's alcove). */
    static final int CASE_ART_CROP_U = 134;
    static final int CASE_ART_CROP_V = 198;
    static final int CASE_ART_CROP_WIDTH = 469;
    static final int CASE_ART_CROP_HEIGHT = 501;
    /** The fallback layout's own even spacing, independent of the hand-painted 3-column positions. */
    private static final int FALLBACK_CARDS_WIDTH = 1404;
    private static final int FALLBACK_CARD_GAP = 14;
    /** The dark backing drawn behind the fallback layout's cards, where the art has no case painted. */
    private static final int FALLBACK_BACKING_LEFT = 126;
    private static final int FALLBACK_BACKING_RIGHT = 1547;
    private static final int FALLBACK_BACKING_BOTTOM = 823;

    // ---------------------------------------------------------------- thumbnail preview strip
    private static final int THUMBNAIL_X = 226;
    private static final int THUMBNAIL_STRIDE = 207;
    private static final int THUMBNAIL_Y = 723;
    private static final int THUMBNAIL_WIDTH = 173;
    private static final int THUMBNAIL_HEIGHT = 84;

    // ---------------------------------------------------------------- footer row (pagination)
    private static final int FOOTER_Y = 833;
    private static final int FOOTER_HEIGHT = 51;
    private static final int PREVIOUS_X = 546;
    private static final int PREVIOUS_WIDTH = 191;
    private static final int NEXT_X = 936;
    private static final int NEXT_WIDTH = 192;
    private static final int BACK_X = 1374;
    private static final int BACK_WIDTH = 172;
    private static final int COUNTS_Y = 854;
    private static final int COUNTS_X = 136;
    private static final int COUNTS_WIDTH = 392;
    private static final int PAGE_INDICATOR_X = 750;
    private static final int PAGE_INDICATOR_WIDTH = 174;

    public static TrophyGalleryLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(0, Math.min(MAX_WIDTH, screenWidth - SCREEN_MARGIN));
        int height = Math.max(0, Math.min(MAX_HEIGHT, screenHeight - SCREEN_MARGIN));
        // Re-derive whichever axis is looser from the tighter one, so the panel is never stretched
        // off the art's own aspect ratio -- a window far wider than it is tall would otherwise hand
        // back a "width" the art has no matching height for, and vice versa.
        if (width >= THREE_COLUMN_WIDTH) {
            width = Math.min(width, height * TEXTURE_WIDTH / TEXTURE_HEIGHT);
            height = Math.min(height, width * TEXTURE_HEIGHT / TEXTURE_WIDTH);
        }
        int columns = width >= THREE_COLUMN_WIDTH ? 3 : width >= TWO_COLUMN_WIDTH ? 2 : 1;
        boolean thumbnails = height >= THUMBNAIL_MIN_HEIGHT;
        return new TrophyGalleryLayout((screenWidth - width) / 2, (screenHeight - height) / 2,
                width, height, columns, thumbnails);
    }

    /** A texture-space x, converted to this layout's screen space. */
    public int px(int textureX) {
        return x + Math.round(textureX * (float) width / TEXTURE_WIDTH);
    }

    /** A texture-space y, converted to this layout's screen space. */
    public int py(int textureY) {
        return y + Math.round(textureY * (float) height / TEXTURE_HEIGHT);
    }

    /** A texture-space width, scaled to this layout -- unlike {@link #px} this is a length, not a position. */
    public int dx(int textureWidth) {
        return Math.round(textureWidth * (float) width / TEXTURE_WIDTH);
    }

    /** A texture-space height, scaled to this layout -- unlike {@link #py} this is a length, not a position. */
    public int dy(int textureHeight) {
        return Math.round(textureHeight * (float) height / TEXTURE_HEIGHT);
    }

    /** Too small to draw a single readable card in -- the screen falls back to a "make room" message. */
    public boolean usable() {
        return width >= MIN_USABLE_WIDTH && height >= MIN_USABLE_HEIGHT;
    }

    public int headerY() {
        return py(HEADER_Y);
    }

    public int headerHeight() {
        return dy(HEADER_HEIGHT);
    }

    public int searchX() {
        return px(SEARCH_X);
    }

    public int searchWidth() {
        return dx(SEARCH_WIDTH);
    }

    public int tierButtonX() {
        return px(TIER_BUTTON_X);
    }

    public int tierButtonWidth() {
        return dx(TIER_BUTTON_WIDTH);
    }

    public int shinyButtonX() {
        return px(SHINY_BUTTON_X);
    }

    public int shinyButtonWidth() {
        return dx(SHINY_BUTTON_WIDTH);
    }

    public int sortButtonX() {
        return px(SORT_BUTTON_X);
    }

    public int sortButtonWidth() {
        return dx(SORT_BUTTON_WIDTH);
    }

    public int titleX() {
        return px(TITLE_X);
    }

    public int titleY() {
        return py(TITLE_Y);
    }

    public int titleWidth() {
        return dx(TITLE_WIDTH);
    }

    public int subtitleX() {
        return px(SUBTITLE_X);
    }

    public int subtitleY() {
        return py(SUBTITLE_Y);
    }

    public int subtitleWidth() {
        return dx(SUBTITLE_WIDTH);
    }

    public boolean showsSubtitle() {
        return width >= THREE_COLUMN_WIDTH;
    }

    public int brandX() {
        return px(BRAND_X);
    }

    public int brandY() {
        return py(BRAND_Y);
    }

    public int brandWidth() {
        return dx(BRAND_WIDTH);
    }

    public boolean showsBrand() {
        return width >= WIDE_HEADING_WIDTH;
    }

    public int bodyY() {
        return py(CASE_TOP);
    }

    public int bodyHeight() {
        int bottom = py(thumbnails ? CASE_BOTTOM_WITH_THUMBNAILS : CASE_BOTTOM_WITHOUT_THUMBNAILS);
        return bottom - bodyY();
    }

    /** True only in the 3-column layout, where the art's own hand-painted case positions apply. */
    public boolean paintedCases() {
        return columns == 3;
    }

    public int cardX(int index) {
        if (paintedCases()) return px(CASE_X[index]);
        return px(CASE_X[0]) + index * (fallbackCardWidth() + dx(FALLBACK_CARD_GAP));
    }

    public int cardWidth(int index) {
        if (paintedCases()) return dx(CASE_WIDTH[index]);
        return fallbackCardWidth();
    }

    private int fallbackCardWidth() {
        return (dx(FALLBACK_CARDS_WIDTH) - (columns - 1) * dx(FALLBACK_CARD_GAP)) / columns;
    }

    public int fallbackBackingLeft() {
        return px(FALLBACK_BACKING_LEFT);
    }

    public int fallbackBackingRight() {
        return px(FALLBACK_BACKING_RIGHT);
    }

    public int fallbackBackingBottom() {
        return py(FALLBACK_BACKING_BOTTOM);
    }

    public int thumbnailX(int index) {
        return px(THUMBNAIL_X + THUMBNAIL_STRIDE * index);
    }

    public int thumbnailY() {
        return py(THUMBNAIL_Y);
    }

    public int thumbnailWidth() {
        return dx(THUMBNAIL_WIDTH);
    }

    public int thumbnailHeight() {
        return dy(THUMBNAIL_HEIGHT);
    }

    public int footerY() {
        return py(FOOTER_Y);
    }

    public int footerHeight() {
        return dy(FOOTER_HEIGHT);
    }

    public int previousButtonX() {
        return px(PREVIOUS_X);
    }

    public int previousButtonWidth() {
        return dx(PREVIOUS_WIDTH);
    }

    public int nextButtonX() {
        return px(NEXT_X);
    }

    public int nextButtonWidth() {
        return dx(NEXT_WIDTH);
    }

    public int backButtonX() {
        return px(BACK_X);
    }

    public int backButtonWidth() {
        return dx(BACK_WIDTH);
    }

    public int countsY() {
        return py(COUNTS_Y);
    }

    public int countsX() {
        return px(COUNTS_X);
    }

    public int countsWidth() {
        return dx(COUNTS_WIDTH);
    }

    public int pageIndicatorX() {
        return px(PAGE_INDICATOR_X);
    }

    public int pageIndicatorWidth() {
        return dx(PAGE_INDICATOR_WIDTH);
    }
}
