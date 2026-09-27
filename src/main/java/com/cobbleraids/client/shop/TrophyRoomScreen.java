package com.cobbleraids.client.shop;

import com.cobbleraids.catching.TrophyGalleryQuery;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * "Hall of Victories": a browsable, permanent record of every species a player has ever defeated in
 * a raid, searchable and filterable.
 *
 * <p>A bespoke panel rather than {@link RaidShopScreen}'s sliced-art grid -- see
 * {@link TrophyGalleryLayout}'s own header comment for why. Almost the whole frame -- the three
 * display-case alcoves, the header/footer button plates, the thumbnail slots -- is one piece of art
 * ({@code gallery.png}); this class draws real widgets and text on top of it rather than drawing its
 * own chrome, and only falls back to cropping one alcove per card at the 1- and 2-column layouts the
 * art has no room to hand-paint. It still reuses {@link ShopSpeciesIcons}/{@link ShopPokemonPortraits}
 * for species art, which is why this lives in the same package as the shop screens rather than a new
 * one: those two are package-private, and widening them for a second caller would be more surface
 * than a second small screen needs.
 *
 * <p>Read-only: there is nothing to buy here, only pages to turn and filters to change. Every
 * request round-trips through the server ({@link com.cobbleraids.catching.TrophyRoomGateway}), which
 * re-runs {@link TrophyGalleryQuery} itself -- this screen never filters or sorts a list on its own,
 * so what it shows can never disagree with what the server would show for the same request.
 */
public final class TrophyRoomScreen extends Screen {

    private static final ResourceLocation GALLERY = ResourceLocation.fromNamespaceAndPath(
            "cobbleraids", "textures/gui/trophy_gallery/gallery.png");

    private static final int CYAN = 0xFF39F2FF;
    private static final int TEXT_WHITE = 0xFFF1FBFF;
    private static final int DIMMED_TEXT = 0xFF76A3B8;
    private static final int BRAND_TEXT = 0xFFB4EEFF;
    /** The thumbnail strip's selected-slot outline; a shade lighter than {@link #CYAN} so it reads as
     * a highlight on top of the strip's own cyan-bordered slots rather than blending into them. */
    private static final int THUMBNAIL_HIGHLIGHT = 0xFFB5FFFF;
    private static final int BACKING_FILL = CYAN;

    private static final DateTimeFormatter FIRST_DEFEATED_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());

    private static TrophyRoomScreen open;

    private TrophyRoomPagePayload page;
    private TrophyGalleryLayout layout;
    private final List<Case> cases = new ArrayList<>();

    private String query = "";
    private RaidRarityTier tier; // null means every tier
    private TrophyGalleryQuery.Shiny shiny = TrophyGalleryQuery.Shiny.ALL;
    private TrophyGalleryQuery.Sort sort = TrophyGalleryQuery.Sort.NEWEST;
    /** Which case in {@link #page}'s preview strip is highlighted; purely cosmetic, see {@link #drawThumbnails}. */
    private int highlightedThumbnail;
    /** Ticks left before a search-box edit fires a request; -1 means nothing is pending. */
    private int searchDebounceTicks = -1;
    /** True from the moment a request is sent until its reply arrives, so a slow link cannot queue up
     * several redundant requests from a player mashing the page-turn button. */
    private boolean awaitingReply;
    /** The column count the currently-displayed {@link #page} was actually requested with -- compared
     * against {@link TrophyGalleryLayout#columns()} on every relayout, since a resize that changes the
     * page size makes the current page index mean something different on the server. */
    private int columnsOfCurrentPage = TrophyGalleryQuery.MAX_COLUMNS;

    private EditBox searchBox;

    private TrophyRoomScreen(TrophyRoomPagePayload page) {
        super(Component.literal("Trophy Room"));
        this.page = page;
    }

    /** Opens the screen, or refreshes the one already open. Called from the payload receiver. */
    public static void show(TrophyRoomPagePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (open != null && client.screen == open) {
            open.applyPage(payload);
            return;
        }
        open = new TrophyRoomScreen(payload);
        client.setScreen(open);
    }

    /**
     * A new page arrived: the counts, the cases and -- because the Previous/Next buttons' active
     * state and the "x / y" page label are baked into the widgets built for the page they were built
     * for, not read live off {@link #page} -- the header and footer widgets too.
     *
     * <p>Rebuilding widgets loses focus by default, which would otherwise make the search box drop
     * focus on every reply while a player is still typing (the very case the debounce in
     * {@link #buildHeaderRow} exists for), so focus is captured before the rebuild and restored after.
     */
    private void applyPage(TrophyRoomPagePayload payload) {
        this.page = payload;
        this.awaitingReply = false;
        this.highlightedThumbnail = 0;
        boolean searchWasFocused = searchBox != null && searchBox.isFocused();
        buildWidgets();
        if (searchWasFocused) setFocused(searchBox);
        // The window did not change shape here -- init() already re-requests page 0 when it does --
        // but a filter/sort/search change always lands on page 0 server-side regardless of what was
        // requested, so there is nothing else to reconcile.
    }

    @Override
    protected void init() {
        layout = TrophyGalleryLayout.fit(width, height);
        buildWidgets();

        // The window changed shape since the page we are showing was fetched: its size no longer
        // matches what the server paginated by, so page 0 under the new size is the only index that
        // is still guaranteed to mean anything.
        if (layout.usable() && layout.columns() != columnsOfCurrentPage) {
            request(0);
        }
    }

    private void buildWidgets() {
        clearWidgets();
        cases.clear();
        if (layout == null || !layout.usable()) return;
        buildHeaderRow();
        buildFooterRow();
        rebuildCases();
    }

    private void buildHeaderRow() {
        int y = layout.headerY();
        int h = layout.headerHeight();

        // Borderless and padded inward: the search field's own box is already painted into the art
        // at this position, so drawing vanilla's box border on top of it would double up the edge.
        searchBox = new EditBox(font, layout.searchX() + 8, y + (h - 8) / 2, layout.searchWidth() - 16, 12,
                Component.literal("Search species"));
        searchBox.setBordered(false);
        searchBox.setTextColor(TEXT_WHITE);
        searchBox.setMaxLength(TrophyRoomActionPayload.MAX_QUERY_LENGTH);
        searchBox.setValue(query);
        searchBox.setHint(Component.literal("Search species..."));
        // Debounced rather than one request per keystroke: a name typed at normal speed is a dozen
        // network round trips otherwise, for a result nobody was going to look at until they stopped.
        searchBox.setResponder(value -> {
            query = value;
            searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(new GalleryButton(layout.tierButtonX(), y, layout.tierButtonWidth(), h,
                tier == null ? "All Tiers" : tier.displayName(), false, () -> {
            tier = nextTier(tier);
            request(0);
        }));
        addRenderableWidget(new GalleryButton(layout.shinyButtonX(), y, layout.shinyButtonWidth(), h,
                shinyLabel(shiny), false, () -> {
            shiny = shiny.next();
            request(0);
        }));
        addRenderableWidget(new GalleryButton(layout.sortButtonX(), y, layout.sortButtonWidth(), h,
                sortLabel(sort), false, () -> {
            sort = sort.next();
            request(0);
        }));
    }

    private void buildFooterRow() {
        int y = layout.footerY();
        int h = layout.footerHeight();
        int previousIndex = page.pageIndex() - 1;
        int nextIndex = page.pageIndex() + 1;

        addRenderableWidget(activeIf(new GalleryButton(layout.previousButtonX(), y, layout.previousButtonWidth(), h,
                layout.width() >= 600 ? "< Previous" : "< Prev", false, () -> request(previousIndex)),
                page.pageIndex() > 0));
        addRenderableWidget(activeIf(new GalleryButton(layout.nextButtonX(), y, layout.nextButtonWidth(), h,
                        "Next >", false, () -> request(nextIndex)),
                nextIndex < page.pageCount()));
        // alwaysClickable: closing the screen must work even while a request is in flight, unlike
        // every other button here, which a pending reply disables.
        addRenderableWidget(new GalleryButton(layout.backButtonX(), y, layout.backButtonWidth(), h,
                "Back", true, this::onClose));
    }

    private static GalleryButton activeIf(GalleryButton button, boolean active) {
        button.active = active;
        return button;
    }

    /**
     * One clickable hotspot per currently-shown case, laid out over {@link TrophyGalleryLayout#cardX}.
     * Rebuilt whenever the page or the layout changes; the cases themselves are drawn in {@link #render}.
     */
    private void rebuildCases() {
        cases.clear();
        int shown = Math.min(layout.columns(), page.entries().size());
        for (int i = 0; i < shown; i++) {
            cases.add(new Case(layout.cardX(i), layout.bodyY(), layout.cardWidth(i), layout.bodyHeight(),
                    page.entries().get(i)));
        }
    }

    private static final int SEARCH_DEBOUNCE_TICKS = 8; // 0.4s at 20 ticks/sec

    @Override
    public void tick() {
        if (searchDebounceTicks > 0) searchDebounceTicks--;
        else if (searchDebounceTicks == 0 && !awaitingReply) {
            searchDebounceTicks = -1;
            request(0);
        }
    }

    private void request(int index) {
        if (awaitingReply) return;
        searchDebounceTicks = -1;
        awaitingReply = true;
        columnsOfCurrentPage = layout.columns();
        ClientPlayNetworking.send(new TrophyRoomActionPayload(Math.max(0, index), layout.columns(),
                query, tier == null ? "" : tier.serializedName(), shiny.ordinal(), sort.ordinal()));
    }

    /** See {@link RaidShopScreen#removed()} for why cleanup lives here rather than in onClose(). */
    @Override
    public void removed() {
        if (open == this) open = null;
        ShopPokemonPortraits.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A true no-op. {@code Screen.render()} (invoked below via {@code super.render()}) unconditionally
     * calls this method again after our own {@link #renderTransparentBackground} call below --
     * overriding it stops that second, vanilla-dispatched call from re-triggering
     * {@code renderBlurredBackground()}. Same fix, same reason, as
     * {@link com.cobbleraids.client.reveal.RaidRewardRevealScreen#renderBackground}; see that class
     * for the fuller story of how this was root-caused.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.renderTransparentBackground(graphics);
        if (layout == null || !layout.usable()) {
            graphics.drawCenteredString(font, "Not enough room to show the trophy room.",
                    width / 2, height / 2, TEXT_WHITE);
            return;
        }

        drawFrame(graphics);
        drawHeading(graphics);
        drawBody(graphics, partialTicks);
        drawCounts(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);
        if (layout.thumbnails() && !awaitingReply) drawThumbnails(graphics, partialTicks);
        if (!awaitingReply) drawHoverTooltip(graphics, mouseX, mouseY);
    }

    /**
     * The whole panel is one texture, scaled to fill the layout -- frame, case alcoves, button
     * plates and thumbnail slots are all painted into it once rather than assembled from separate
     * flat-fill pieces. At the 1- and 2-column layouts the art only has one alcove painted (there is
     * nowhere to paint two unevenly-sized ones for every possible window size), so a fallback backing
     * plus a crop of that one alcove stands in for each card there instead.
     */
    private void drawFrame(GuiGraphics graphics) {
        graphics.blit(GALLERY, layout.x(), layout.y(), layout.width(), layout.height(),
                0, 0, TrophyGalleryLayout.TEXTURE_WIDTH, TrophyGalleryLayout.TEXTURE_HEIGHT,
                TrophyGalleryLayout.TEXTURE_WIDTH, TrophyGalleryLayout.TEXTURE_HEIGHT);
        if (layout.paintedCases()) return;

        graphics.fill(layout.fallbackBackingLeft(), layout.bodyY(),
                layout.fallbackBackingRight(), layout.fallbackBackingBottom(), BACKING_FILL);
        for (int i = 0; i < layout.columns(); i++) {
            graphics.blit(GALLERY, layout.cardX(i), layout.bodyY(), layout.cardWidth(i), layout.bodyHeight(),
                    TrophyGalleryLayout.CASE_ART_CROP_U, TrophyGalleryLayout.CASE_ART_CROP_V,
                    TrophyGalleryLayout.CASE_ART_CROP_WIDTH, TrophyGalleryLayout.CASE_ART_CROP_HEIGHT,
                    TrophyGalleryLayout.TEXTURE_WIDTH, TrophyGalleryLayout.TEXTURE_HEIGHT);
        }
    }

    private void drawHeading(GuiGraphics graphics) {
        drawFitted(graphics, "Trophy Room", layout.titleX(), layout.titleY(), layout.titleWidth(), TEXT_WHITE);
        if (layout.showsSubtitle()) {
            drawFittedCentered(graphics, "HALL OF VICTORIES",
                    layout.subtitleX(), layout.subtitleY(), layout.subtitleWidth(), CYAN);
        }
        if (layout.showsBrand()) {
            drawFitted(graphics, "COBBLEMON RAID NETWORK",
                    layout.brandX(), layout.brandY(), layout.brandWidth(), BRAND_TEXT);
        }
    }

    /**
     * One slot per column, always -- unlike the shop grid, an empty or still-loading slot draws its
     * own state rather than one message centred over the whole body, so a player paging past the end
     * of a short list sees which specific cases are empty instead of losing the frame's cases entirely.
     */
    private void drawBody(GuiGraphics graphics, float partialTicks) {
        for (int i = 0; i < layout.columns(); i++) {
            if (!awaitingReply && i < cases.size()) {
                drawCase(graphics, cases.get(i), i == highlightedThumbnail, partialTicks);
                continue;
            }
            int x = layout.cardX(i);
            int y = layout.bodyY();
            int w = layout.cardWidth(i);
            int h = layout.bodyHeight();
            String top = awaitingReply ? "Loading..." : "Unclaimed";
            String bottom = !awaitingReply && page.total() > 0 && page.entries().isEmpty()
                    ? "No matches" : "Win a raid";
            drawFittedCentered(graphics, top, x + w / 5, y + (int) (h * 0.69), w * 3 / 5, DIMMED_TEXT);
            drawFittedCentered(graphics, bottom, x + w / 5, y + (int) (h * 0.82), w * 3 / 5, TEXT_WHITE);
        }
    }

    private void drawCounts(GuiGraphics graphics) {
        int y = layout.countsY();
        drawFitted(graphics, page.total() + " TROPHIES | " + page.shinyTotal() + " SHINY",
                layout.countsX(), y, layout.countsWidth(), 0xFFBEF8FF);
        drawFittedCentered(graphics, (page.pageIndex() + 1) + " / " + page.pageCount(),
                layout.pageIndicatorX(), y, layout.pageIndicatorWidth(), TEXT_WHITE);
    }

    /**
     * The preview strip: every trophy this page's request fetched, including the next page's share
     * (see {@link TrophyGalleryQuery.Page}'s own javadoc for why there are up to twice as many as are
     * shown as full cases). Clicking one within the currently-shown cases only highlights it -- it is
     * already fully visible as a case, so there is nothing else useful for a click to do. Clicking one
     * from the next page's share turns there; see {@link #onThumbnailClicked}.
     */
    private void drawThumbnails(GuiGraphics graphics, float partialTicks) {
        for (int i = 0; i < page.entries().size(); i++) {
            int x = layout.thumbnailX(i);
            int y = layout.thumbnailY();
            int w = layout.thumbnailWidth();
            int h = layout.thumbnailHeight();
            int size = Math.min(w - 6, h - 4);
            drawSpecies(graphics, page.entries().get(i), x + (w - size) / 2, y + (h - size) / 2, size, partialTicks);
            if (i == highlightedThumbnail) {
                graphics.renderOutline(x, y, w, h, CYAN);
                graphics.renderOutline(x + 1, y + 1, w - 2, h - 2, THUMBNAIL_HIGHLIGHT);
            }
        }
    }

    private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Case c : cases) {
            if (mouseX < c.x || mouseX >= c.x + c.width || mouseY < c.y || mouseY >= c.y + c.height) continue;
            TrophyRoomEntryPayload entry = c.entry;
            graphics.renderComponentTooltip(font, List.of(
                    Component.literal(speciesTitle(entry.species()) + " - Level " + entry.level()),
                    Component.literal("First defeat: " + FIRST_DEFEATED_FORMAT.format(
                            Instant.ofEpochMilli(entry.firstDefeatedAtEpochMs()))),
                    Component.literal("Defeated " + entry.timesDefeated() + " time"
                            + (entry.timesDefeated() == 1 ? "" : "s"))), mouseX, mouseY);
            return;
        }
    }

    /**
     * Everything here is a fraction of the case's own size rather than a fixed pixel offset, since
     * {@link TrophyGalleryLayout#cardWidth} genuinely differs between the hand-painted 3-column case
     * and the cropped 1-/2-column fallback -- a fixed offset tuned for one would misplace text in
     * the other.
     */
    private void drawCase(GuiGraphics graphics, Case c, boolean highlighted, float partialTicks) {
        int spriteSize = Math.min((int) (c.width * 0.78), (int) (c.height * 0.52));
        drawSpecies(graphics, c.entry, c.x + (c.width - spriteSize) / 2, c.y + (int) (c.height * 0.09),
                spriteSize, partialTicks);

        int plateX = c.x + (int) (c.width * 0.21);
        int plateWidth = (int) (c.width * 0.57);
        drawFittedCentered(graphics, speciesTitle(c.entry.species()),
                plateX, c.y + (int) (c.height * 0.685), plateWidth, TEXT_WHITE);

        RaidRarityTier entryTier = parseTier(c.entry.tier());
        int tierColor = entryTier == null ? CYAN : (0xFF000000 | RaidTierPresentation.color(entryTier).getColor());
        String tierName = entryTier == null ? c.entry.tier() : entryTier.displayName();
        int tagY = c.y + (int) (c.height * 0.75);
        int tagHeight = Math.max(10, (int) (c.height * 0.048));
        graphics.fill(plateX, tagY, plateX + plateWidth, tagY + tagHeight, tierColor);
        drawFittedCentered(graphics, tierName.toUpperCase(Locale.ROOT),
                plateX, tagY + (tagHeight - 8) / 2, plateWidth, 0xFF092238);

        drawFittedCentered(graphics, c.entry.shiny() ? "* SHINY" : "NORMAL",
                plateX, c.y + (int) (c.height * 0.83), plateWidth, c.entry.shiny() ? 0xFFFFDE58 : TEXT_WHITE);
        drawFittedCentered(graphics, "IV " + c.entry.ivPercent() + "% | EV " + c.entry.evPercent() + "%",
                c.x + 6, c.y + (int) (c.height * 0.916), c.width - 12, TEXT_WHITE);
    }

    private void drawSpecies(GuiGraphics graphics, TrophyRoomEntryPayload entry, int x, int y, int size, float partialTicks) {
        ResourceLocation texture = ShopSpeciesIcons.texture(entry.species(), entry.shiny());
        if (texture != null) {
            int h = Math.max(1, size * ShopSpeciesIcons.HEIGHT / ShopSpeciesIcons.WIDTH);
            graphics.blit(texture, x, y + (size - h) / 2, size, h, 0, 0,
                    ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT, ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT);
            return;
        }
        if (!ShopPokemonPortraits.draw(graphics, entry.species(), entry.shiny(), x, y, size, partialTicks)) {
            graphics.drawCenteredString(font, "?", x + size / 2, y + size / 2, TEXT_WHITE);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && layout != null && layout.thumbnails() && !awaitingReply) {
            if (onThumbnailClicked(mouseX, mouseY)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean onThumbnailClicked(double mouseX, double mouseY) {
        for (int i = 0; i < page.entries().size(); i++) {
            int x = layout.thumbnailX(i);
            int y = layout.thumbnailY();
            int w = layout.thumbnailWidth();
            int h = layout.thumbnailHeight();
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) continue;
            if (i >= layout.columns()) {
                request(page.pageIndex() + 1);
            } else {
                highlightedThumbnail = i;
                playClick();
            }
            return true;
        }
        return false;
    }

    private void playClick() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    // ---------------------------------------------------------------- small drawing/text helpers

    private void drawFitted(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        if (width <= 0) return;
        graphics.drawString(font, truncate(text, width), x, y, color, false);
    }

    private void drawFittedCentered(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        String fitted = truncate(text, width);
        graphics.drawString(font, fitted, x + (width - font.width(fitted)) / 2, y, color, false);
    }

    private String truncate(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - 6)) + "…";
    }

    private static String speciesTitle(String species) {
        return species.isEmpty() ? species
                : Character.toUpperCase(species.charAt(0)) + species.substring(1).replace('_', ' ');
    }

    private static RaidRarityTier parseTier(String value) {
        try {
            return RaidRarityTier.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static RaidRarityTier nextTier(RaidRarityTier current) {
        RaidRarityTier[] tiers = RaidRarityTier.values();
        if (current == null) return tiers[0];
        int next = current.ordinal() + 1;
        return next >= tiers.length ? null : tiers[next];
    }

    private static String shinyLabel(TrophyGalleryQuery.Shiny shiny) {
        return switch (shiny) {
            case ALL -> "Shiny: All";
            case SHINY_ONLY -> "Shiny only";
            case NORMAL_ONLY -> "Normal only";
        };
    }

    private static String sortLabel(TrophyGalleryQuery.Sort sort) {
        return switch (sort) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case SPECIES -> "Species";
            case BEST_IV -> "Best IV";
        };
    }

    /** One display case's hotspot and the trophy it shows. Rebuilt by {@link #rebuildCases}. */
    private record Case(int x, int y, int width, int height, TrophyRoomEntryPayload entry) {
    }

    /**
     * A button drawn directly over the art's own baked-in plate shape: unlike a vanilla button, this
     * draws no background of its own, only a hover outline and its label -- see the class javadoc for
     * why the art carries almost all of this screen's chrome.
     *
     * <p>{@code alwaysClickable} exists for exactly one button (Back): every other button is inert
     * while a request is in flight, so mashing a filter cannot queue up several stale requests, but
     * closing the screen must always work. An earlier draft of this screen decided that by comparing
     * the button's own label text to the literal string {@code "Back"} -- this flag says the same
     * thing without silently breaking the moment that label is ever reworded or translated.
     */
    private final class GalleryButton extends AbstractButton {
        private final Runnable action;
        private final boolean alwaysClickable;

        GalleryButton(int x, int y, int width, int height, String label, boolean alwaysClickable, Runnable action) {
            super(x, y, width, height, Component.literal(label));
            this.alwaysClickable = alwaysClickable;
            this.action = action;
        }

        @Override
        public void onPress() {
            if (alwaysClickable || !awaitingReply) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            boolean enabled = active && (alwaysClickable || !awaitingReply);
            if (isHoveredOrFocused() && enabled) {
                graphics.renderOutline(getX() + 2, getY() + 2, getWidth() - 4, getHeight() - 4, CYAN);
            }
            drawFittedCentered(graphics, getMessage().getString(), getX() + 4,
                    getY() + (getHeight() - 8) / 2, getWidth() - 8, enabled ? TEXT_WHITE : DIMMED_TEXT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
