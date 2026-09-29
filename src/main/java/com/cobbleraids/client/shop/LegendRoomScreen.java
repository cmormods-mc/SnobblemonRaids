package com.cobbleraids.client.shop;

import com.cobbleraids.catching.LegendGalleryQuery;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.LegendActionPayload;
import com.cobbleraids.network.LegendEntryPayload;
import com.cobbleraids.network.LegendPagePayload;
import com.cobbleraids.presentation.RaidTierPresentation;
import com.cobbleraids.renown.RenownBoon;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The server's wall of fame: the first-ever defeat of every distinct renowned title on every distinct
 * species, server-wide, newest first -- with an in-screen "Mine" toggle to narrow it to legends the
 * viewing player personally holds.
 *
 * <p>Unlike {@link TrophyRoomScreen}, there is no bespoke art for this screen: a legend row (title,
 * species, tier, boon, date, up to four victor names) is a different information shape than a
 * species display case, and {@code gallery.png}'s hand-painted alcove positions are tuned
 * specifically for that card, not a chronological text list. This screen draws its own flat chrome
 * instead, the same technique the Raid Capture Protocol's screens use for their own native art (see
 * {@code com.cobbleraids.client.capture.CaptureUi}) -- no new art commissioned.
 *
 * <p>Read-only, same reasoning as {@link TrophyRoomScreen}: nothing to buy or spend here, only pages
 * to turn and a filter to change.
 */
public final class LegendRoomScreen extends Screen {

    private static final int NAVY = 0xFF03283F;
    private static final int CYAN = 0xFF39F2FF;
    private static final int TEXT_WHITE = 0xFFF1FBFF;
    private static final int DIMMED_TEXT = 0xFF76A3B8;

    private static final DateTimeFormatter DEFEATED_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());

    private static LegendRoomScreen open;

    private LegendPagePayload page;
    private LegendGalleryLayout layout;

    private boolean mineOnly;
    private RaidRarityTier tier; // null means every tier
    private boolean awaitingReply;
    /** Same reasoning as TrophyRoomScreen#columnsOfCurrentPage: a resize changes the server's page size. */
    private int rowsOfCurrentPage = LegendGalleryQuery.MAX_COLUMNS;

    private LegendRoomScreen(LegendPagePayload page) {
        super(Component.literal("Hall of Legends"));
        this.page = page;
    }

    /** Opens the screen, or refreshes the one already open. Called from the payload receiver. */
    public static void show(LegendPagePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (open != null && client.screen == open) {
            open.applyPage(payload);
            return;
        }
        open = new LegendRoomScreen(payload);
        client.setScreen(open);
    }

    private void applyPage(LegendPagePayload payload) {
        this.page = payload;
        this.awaitingReply = false;
        buildWidgets();
    }

    @Override
    protected void init() {
        layout = LegendGalleryLayout.fit(width, height);
        buildWidgets();

        if (layout.usable() && layout.rows() != rowsOfCurrentPage) {
            request(0);
        }
    }

    private void buildWidgets() {
        clearWidgets();
        if (layout == null || !layout.usable()) return;
        buildHeaderRow();
        buildFooterRow();
    }

    private void buildHeaderRow() {
        int y = layout.headerY();
        int h = layout.headerHeight();
        int buttonWidth = (layout.width() - 8) / 2;

        addRenderableWidget(new LegendButton(layout.x(), y, buttonWidth, h,
                mineOnly ? "Mine" : "All", false, () -> {
            mineOnly = !mineOnly;
            request(0);
        }));
        addRenderableWidget(new LegendButton(layout.x() + buttonWidth + 8, y, buttonWidth, h,
                tier == null ? "All Tiers" : tier.displayName(), false, () -> {
            tier = nextTier(tier);
            request(0);
        }));
    }

    private void buildFooterRow() {
        int y = layout.footerY();
        int h = layout.footerHeight();
        int previousIndex = page.pageIndex() - 1;
        int nextIndex = page.pageIndex() + 1;
        int backWidth = 70;
        int navWidth = (layout.width() - backWidth - 8) / 2;

        addRenderableWidget(activeIf(new LegendButton(layout.x(), y, navWidth, h,
                "< Previous", false, () -> request(previousIndex)), page.pageIndex() > 0));
        addRenderableWidget(activeIf(new LegendButton(layout.x() + navWidth + 4, y, navWidth, h,
                "Next >", false, () -> request(nextIndex)), nextIndex < page.pageCount()));
        addRenderableWidget(new LegendButton(layout.x() + layout.width() - backWidth, y, backWidth, h,
                "Back", true, this::onClose));
    }

    private static LegendButton activeIf(LegendButton button, boolean active) {
        button.active = active;
        return button;
    }

    private void request(int index) {
        if (awaitingReply) return;
        awaitingReply = true;
        rowsOfCurrentPage = layout.rows();
        ClientPlayNetworking.send(new LegendActionPayload(Math.max(0, index), layout.rows(),
                mineOnly, tier == null ? "" : tier.serializedName()));
    }

    @Override
    public void removed() {
        if (open == this) open = null;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True no-op; see reference_screen_background_blur / TrophyRoomScreen's identical override. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.renderTransparentBackground(graphics);
        if (layout == null || !layout.usable()) {
            graphics.drawCenteredString(font, "Not enough room to show the Hall of Legends.",
                    width / 2, height / 2, TEXT_WHITE);
            return;
        }

        panel(graphics, layout.x(), layout.y(), layout.width(), layout.height());
        drawHeading(graphics);
        drawRows(graphics);
        drawCounts(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF001425);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, CYAN);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, NAVY);
    }

    private void drawHeading(GuiGraphics graphics) {
        drawFittedCentered(graphics, "HALL OF LEGENDS", layout.x() + 4, layout.headerY() + 2,
                layout.width() - 8, TEXT_WHITE);
    }

    private void drawRows(GuiGraphics graphics) {
        int rows = layout.rows();
        for (int i = 0; i < rows; i++) {
            int y = layout.rowY(i);
            int h = layout.rowHeight();
            if (awaitingReply || i >= page.entries().size()) {
                if (!awaitingReply && i == 0) {
                    drawFittedCentered(graphics, page.total() == 0 ? "No legends recorded yet."
                                    : "No legends match this filter.",
                            layout.x() + 8, y + h / 2 - 4, layout.width() - 16, DIMMED_TEXT);
                }
                continue;
            }
            drawRow(graphics, page.entries().get(i), layout.x() + 4, y, layout.width() - 8, h);
        }
    }

    private void drawRow(GuiGraphics graphics, LegendEntryPayload entry, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF072A45);

        RaidRarityTier entryTier = parseTier(entry.tier());
        int tierColor = entryTier == null ? CYAN : (0xFF000000 | RaidTierPresentation.color(entryTier).getColor());
        graphics.fill(x, y, x + 3, y + h, tierColor);

        int textX = x + 8;
        int textWidth = w - 12;
        drawFitted(graphics, entry.title() + " -- " + speciesTitle(entry.species()), textX, y + 2, textWidth, TEXT_WHITE);
        drawFitted(graphics, (entryTier == null ? entry.tier() : entryTier.displayName()).toUpperCase(Locale.ROOT)
                + "  |  " + boonLabel(entry.boon()) + "  |  " + DEFEATED_FORMAT.format(
                        Instant.ofEpochMilli(entry.defeatedAtEpochMs())), textX, y + 13, textWidth, DIMMED_TEXT);
        drawFitted(graphics, String.join(", ", entry.victorNames()), textX, y + 23, textWidth, CYAN);
    }

    private void drawCounts(GuiGraphics graphics) {
        int y = layout.footerY() - 11;
        String label = mineOnly ? page.mineCount() + " OF YOURS" : page.total() + " LEGENDS";
        drawFittedCentered(graphics, label + "  |  " + (page.pageIndex() + 1) + " / " + page.pageCount(),
                layout.x(), y, layout.width(), 0xFFBEF8FF);
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

    private static String boonLabel(String encoded) {
        return RenownBoon.decode(encoded).map(boon -> switch (boon.kind()) {
            case NONE -> "No Boon";
            case HP_POOL -> "HP Pool";
            case STAT_FOCUS -> capitalizeWords(boon.stat().replace('_', ' ')) + " Focus";
        }).orElse("Unknown Boon");
    }

    private static String capitalizeWords(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
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

    /** A flat, borderless button matching this screen's own chrome rather than vanilla's beveled one. */
    private final class LegendButton extends AbstractButton {
        private final Runnable action;
        private final boolean alwaysClickable;

        LegendButton(int x, int y, int width, int height, String label, boolean alwaysClickable, Runnable action) {
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
            panel(graphics, getX(), getY(), getWidth(), getHeight());
            if (isHoveredOrFocused() && enabled) {
                graphics.renderOutline(getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, CYAN);
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
