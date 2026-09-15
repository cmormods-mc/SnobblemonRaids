package com.cobbleraids.client.reveal;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Native raid-reward reveal screen: pick a choice, wait for the server's roll, watch the reveal, see
 * what was actually granted. The server remains fully authoritative: this screen only ever sends a
 * choiceId and only ever displays what RewardResultPayload reports back.
 *
 * Presentation uses real texture art (src/main/resources/assets/cobbleraids/textures/gui/raid_rewards),
 * laid out at the art's native 1672x941 reference resolution and uniformly scaled to fit the actual
 * window (see NATIVE_WIDTH/NATIVE_HEIGHT and Layout.scale). Every native coordinate below comes from
 * the asset pack's own layout.json. summary_panel.png and claim_button_blank.png are edited copies of
 * the delivered art with the dynamic-value regions painted back to the panel's flat background color
 * (sampled from the source image) so live data can be drawn on top without colliding with baked
 * placeholder text -- the static row labels, headers, and icons are still the original baked pixels.
 */
public final class RaidRewardRevealScreen extends Screen {
    private enum State { CHOOSING, WAITING, OPENING, RESULT }

    private static final ResourceLocation BALL_ITEM = ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball");
    private static final long OPENING_DURATION_MILLIS = 600L;

    private static final float NATIVE_WIDTH = 1672f;
    private static final float NATIVE_HEIGHT = 941f;

    // A single full-canvas texture: the frame chrome (title bar, borders, corner brackets) plus a solid
    // backing fill for the interior, so no part of the panel lets the world show through gaps. The
    // exterior corners outside the frame's angular silhouette are transparent, not a rectangular block.
    private static final ResourceLocation POKEDEX_BACKING = texture("pokedex_backing");
    private static final ResourceLocation SUMMARY_PANEL = texture("summary_panel");
    private static final ResourceLocation CHAMBER_BACKGROUND = texture("chamber_background");
    private static final ResourceLocation CLAIM_BUTTON = texture("claim_button");
    private static final ResourceLocation CLAIM_BUTTON_BLANK = texture("claim_button_blank");

    private static final NativeRect BACKING_RECT = new NativeRect(0, 0, 1672, 941);
    private static final NativeRect SUMMARY_PANEL_RECT = new NativeRect(94, 122, 313, 731);
    private static final NativeRect CHAMBER_RECT = new NativeRect(420, 112, 1172, 675);
    private static final NativeRect BUTTON_RECT = new NativeRect(762, 787, 488, 83);
    private static final NativeRect FOOTER_RECT = new NativeRect(430, 787, 1150, 83);

    // Blanked-value row positions, local to SUMMARY_PANEL_RECT's own origin (see summary_panel.png's
    // edit history: masked from the original baked reference at these exact bands).
    private static final int SIDEBAR_LABEL_X = 8;
    private static final int SIDEBAR_ICON_LABEL_X = 100;
    private static final int VALUE_Y_BOSS = 106;
    private static final int VALUE_Y_TIER = 189;
    private static final int VALUE_Y_TIME = 271;
    private static final int VALUE_Y_DAMAGE = 404;
    private static final int VALUE_Y_PARTICIPANTS = 478;
    private static final float SIDEBAR_TEXT_SCALE = 3.0f;
    /** Largest the renown banner draws, in font pixels per screen pixel; a short title must not balloon. */
    private static final float CHAMBER_BANNER_MAX_SCALE = 2.0f;
    /** Top of the banner as a share of the chamber's height: just inside the corner brackets, above the halo. */
    private static final float CHAMBER_BANNER_TOP = 0.045f;

    private record NativeRect(int x, int y, int width, int height) {}
    private record Rect(int x, int y, int width, int height) {
        int centerX() { return x + width / 2; }
        int centerY() { return y + height / 2; }
    }

    private record Layout(int panelX, int panelY, int panelWidth, int panelHeight, float scale,
                           Rect sidebar, Rect chamber, Rect footer) {
        Rect toScreen(NativeRect r) {
            return toScreen(r, panelX, panelY, scale);
        }

        static Rect toScreen(NativeRect r, int panelX, int panelY, float scale) {
            return new Rect(panelX + Math.round(r.x() * scale), panelY + Math.round(r.y() * scale),
                    Math.round(r.width() * scale), Math.round(r.height() * scale));
        }
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("cobbleraids", "textures/gui/raid_rewards/" + name + ".png");
    }

    private final class TexturedButton extends Button {
        private final ResourceLocation buttonTexture;
        private final int textureWidth;
        private final int textureHeight;

        TexturedButton(int x, int y, int width, int height, Component message, OnPress onPress,
                        ResourceLocation buttonTexture, int textureWidth, int textureHeight) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.buttonTexture = buttonTexture;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int alpha = this.isHoveredOrFocused() ? 255 : 220;
            graphics.setColor(1f, 1f, 1f, alpha / 255f);
            graphics.blit(buttonTexture, getX(), getY(), getWidth(), getHeight(),
                    0f, 0f, textureWidth, textureHeight, textureWidth, textureHeight);
            graphics.setColor(1f, 1f, 1f, 1f);
            if (!this.getMessage().getString().isEmpty()) {
                graphics.drawCenteredString(RaidRewardRevealScreen.this.font, this.getMessage(),
                        getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, 0xFFFFFFFF);
            }
        }
    }

    private final PendingRewardRevealPayload pending;
    private final RaidRarityTier tier;
    private final long openedAtMillis = System.currentTimeMillis();
    private State state = State.CHOOSING;
    private long stateEnteredAtMillis = openedAtMillis;
    private RewardResultPayload pendingResult;
    private RewardResultPayload result;

    // Everything below is fixed for the screen's lifetime, so it is built once here rather than
    // rebuilt inside render(). This screen does not pause the game (isPauseScreen() == false), so
    // render() runs at the client's full frame rate -- formatting strings, resolving registry items
    // and allocating ItemStacks per frame was pure churn for values that never change.
    private final Component sidebarSpecies;
    private final Component sidebarTier;
    private final Component sidebarTime;
    private final Component sidebarDamage;
    private final Component sidebarParticipants;
    /** A renowned boss's title, or null. */
    private final Component chamberBanner;

    // Rebuilt in init(), which vanilla also calls on window resize -- the only thing that moves it.
    private Layout layout;
    // Grouped digits, because a five-figure payout is unreadable otherwise. Locale.ROOT so the
    // separator does not follow the client's locale into something the chat line disagrees with.
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getIntegerInstance(Locale.ROOT);

    // Slot colours, picked to read against the chamber's blue without competing with it.
    private static final int SLOT_FILL = 0xBE081828;
    private static final int SLOT_EDGE = 0xDC5ED6FF;
    private static final int CURRENCY_FILL = 0xD22E2206;
    private static final int CURRENCY_EDGE = 0xE6F0BE46;
    private static final int CURRENCY_TEXT = 0xFFFFD666;
    private static final int POINTS_FILL = 0xD2062630;
    private static final int POINTS_EDGE = 0xE64FD6E0;
    private static final int POINTS_TEXT = 0xFF8FE8F2;

    // Built once when the result arrives, not per frame.
    private ItemStack resultIcon;
    private List<Component> resultLines = List.of();
    // The granted items, as stacks, so the receipt can be a row of slots the player recognises
    // rather than a paragraph. Built once on arrival, like everything else here.
    private List<ItemStack> resultStacks = List.of();
    private long resultCurrency;
    private int resultPoints;

    private RaidRewardRevealScreen(PendingRewardRevealPayload pending) {
        super(Component.literal(pending.speciesDisplayName()));
        this.pending = pending;
        this.tier = RaidRarityTier.parse(pending.rarityTier());

        ChatFormatting tierColor = RaidTierPresentation.color(tier);
        long elapsedSeconds = pending.elapsedCombatTicks() / 20L;
        this.sidebarSpecies = Component.literal(pending.speciesDisplayName()).withStyle(tierColor);
        this.sidebarTier = Component.literal(tier.displayName()).withStyle(tierColor);
        this.sidebarTime = Component.literal(String.format(Locale.ROOT, "%02d:%02d",
                elapsedSeconds / 60, elapsedSeconds % 60));
        this.sidebarDamage = Component.literal(String.format(Locale.ROOT, "%.1f%%",
                pending.contributionPercentage()));
        this.sidebarParticipants = Component.literal(Integer.toString(pending.participantCount()));
        this.chamberBanner = RaidBossNameplate.banner(pending.renownTitle());
    }

    public static void openFor(PendingRewardRevealPayload payload) {
        Minecraft.getInstance().setScreen(new RaidRewardRevealScreen(payload));
    }

    /** No-op if the current screen isn't the matching reveal screen -- e.g. the player already closed it. */
    public static void applyResult(RewardResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof RaidRewardRevealScreen screen
                && screen.pending.raidId().equals(payload.raidId())) {
            screen.pendingResult = payload;
            screen.state = State.OPENING;
            screen.stateEnteredAtMillis = System.currentTimeMillis();
            screen.clearWidgets();
            screen.init();
            RevealSounds.playOpen(screen.tier);
            RevealParticles.spawnBurst(screen.layout.chamber().centerX(), screen.layout.chamber().centerY());
        }
    }

    /** Resolves the granted items into display form once, on arrival, instead of once per frame. */
    private void showResult(RewardResultPayload payload) {
        this.result = payload;
        if (payload == null || !payload.success()) {
            this.resultIcon = new ItemStack(BuiltInRegistries.ITEM.get(BALL_ITEM));
            this.resultLines = payload == null
                    ? List.of()
                    : List.of(Component.literal("Something went wrong. Check chat for details."));
            this.resultStacks = List.of();
            this.resultCurrency = 0L;
            this.resultPoints = 0;
            return;
        }
        // A claim can pay currency and no items -- an economy-only reward, or every item line
        // skipped because its mod is gone -- so the currency is what decides whether there is
        // anything to show, not the item list.
        List<ItemStack> stacks = new ArrayList<>(payload.granted().size());
        for (RewardItemPayload item : payload.granted()) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.item()));
            stack.setCount(Math.max(1, item.amount()));
            stacks.add(stack);
        }
        this.resultStacks = List.copyOf(stacks);
        this.resultCurrency = payload.currencyGranted();
        this.resultPoints = payload.raidPointsGranted();
        this.resultIcon = payload.granted().isEmpty()
                ? new ItemStack(BuiltInRegistries.ITEM.get(BALL_ITEM))
                : new ItemStack(BuiltInRegistries.ITEM.get(payload.granted().get(0).item()));
        this.resultLines = List.of();
    }

    private Layout computeLayout() {
        int availableWidth = this.width - 16;
        int availableHeight = this.height - 16;
        float designRatio = NATIVE_WIDTH / NATIVE_HEIGHT;
        int panelWidth = Math.min(640, Math.max(360, availableWidth));
        int panelHeight = Math.round(panelWidth / designRatio);
        if (panelHeight > availableHeight) {
            panelHeight = Math.max(200, availableHeight);
            panelWidth = Math.round(panelHeight * designRatio);
        }
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        float scale = panelWidth / NATIVE_WIDTH;

        return new Layout(panelX, panelY, panelWidth, panelHeight, scale,
                Layout.toScreen(SUMMARY_PANEL_RECT, panelX, panelY, scale),
                Layout.toScreen(CHAMBER_RECT, panelX, panelY, scale),
                Layout.toScreen(FOOTER_RECT, panelX, panelY, scale));
    }

    @Override
    protected void init() {
        this.layout = computeLayout();
        if (state == State.CHOOSING) {
            List<String> choices = pending.choiceIds();
            int gap = Math.round(12 * layout.scale());
            int buttonWidth = (layout.footer().width() - gap * (choices.size() - 1)) / Math.max(1, choices.size());
            int buttonHeight = layout.footer().height();
            int startX = layout.footer().x;
            int y = layout.footer().y;
            int index = 0;
            for (String choiceId : choices) {
                int x = startX + index * (buttonWidth + gap);
                index++;
                this.addRenderableWidget(new TexturedButton(x, y, buttonWidth, buttonHeight,
                        buttonLabel(choiceId, choices.size()), button -> onChoose(choiceId),
                        CLAIM_BUTTON_BLANK, 488, 83));
            }
        } else if (state == State.RESULT) {
            // Centered beneath the chamber specifically (not stretched/centered across the whole footer)
            // -- BUTTON_RECT's native x already bakes in that centering, so the widget bounds below are
            // exactly the rectangle the art was designed against, not re-derived footer-relative math.
            Rect buttonRect = layout.toScreen(BUTTON_RECT);
            this.addRenderableWidget(new TexturedButton(buttonRect.x(), buttonRect.y(),
                    buttonRect.width(), buttonRect.height(), Component.empty(), widget -> this.onClose(),
                    CLAIM_BUTTON, 488, 83));
        }
    }

    /**
     * What the claim button says.
     *
     * <p>A choice id is a datapack key -- every bundled definition uses "all" -- and drawing it
     * raw put the word "all" on a button whose art already says what it does. There is nothing to
     * choose between when there is one option, so it reads as the action it performs. Only a
     * definition that genuinely offers alternatives needs its options named, and then the id is
     * the only name anyone has given them.
     */
    private static Component buttonLabel(String choiceId, int choiceCount) {
        return Component.literal(choiceCount == 1 ? "Claim" : choiceId);
    }

    private void onChoose(String choiceId) {
        ClientPlayNetworking.send(new RewardChoicePayload(pending.raidId(), choiceId));
        this.state = State.WAITING;
        this.stateEnteredAtMillis = System.currentTimeMillis();
        this.clearWidgets();
        this.init();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render() (invoked below via super.render()) unconditionally calls this method again
        // after our own renderTransparentBackground() call -- overriding it to a no-op stops that
        // second, vanilla-dispatched call from re-triggering the blurring renderBackground behavior.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (state == State.OPENING && now - stateEnteredAtMillis >= OPENING_DURATION_MILLIS) {
            showResult(pendingResult);
            state = State.RESULT;
            stateEnteredAtMillis = now;
            clearWidgets();
            init();
        }

        this.renderTransparentBackground(graphics);

        Layout layout = this.layout;
        drawFrame(graphics, layout);
        drawSidebar(graphics, layout);
        drawChamber(graphics, layout, now);

        super.render(graphics, mouseX, mouseY, partialTick);

        switch (state) {
            case WAITING -> graphics.drawCenteredString(this.font, Component.literal("Revealing..."),
                    layout.footer().centerX(), layout.footer().y - 12, ChatFormatting.YELLOW.getColor());
            case OPENING -> graphics.drawCenteredString(this.font, Component.literal("..."),
                    layout.footer().centerX(), layout.footer().y - 12, ChatFormatting.YELLOW.getColor());
            default -> { }
        }

        if (state == State.RESULT) renderResultRow(graphics, layout.chamber(), mouseX, mouseY);

        RevealParticles.renderAndCull(graphics);
    }

    private void drawFrame(GuiGraphics graphics, Layout layout) {
        blitNative(graphics, POKEDEX_BACKING, layout.toScreen(BACKING_RECT), BACKING_RECT);
        blitNative(graphics, SUMMARY_PANEL, layout.sidebar(), SUMMARY_PANEL_RECT);
    }

    private static void blitNative(GuiGraphics graphics, ResourceLocation texture, Rect dest, NativeRect native_) {
        graphics.blit(texture, dest.x(), dest.y(), dest.width(), dest.height(),
                0f, 0f, native_.width(), native_.height(), native_.width(), native_.height());
    }

    private void drawSidebar(GuiGraphics graphics, Layout layout) {
        Rect sidebar = layout.sidebar();
        float textScale = layout.scale() * SIDEBAR_TEXT_SCALE;

        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_BOSS, sidebarSpecies);
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_TIER, sidebarTier);
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_TIME, sidebarTime);
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_ICON_LABEL_X, VALUE_Y_DAMAGE, sidebarDamage);
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_ICON_LABEL_X, VALUE_Y_PARTICIPANTS, sidebarParticipants);
        // Contribution bonus rolls have no row in this layout (the art's "Support Actions" slot was
        // removed rather than repurposed) -- still shown in the existing claim chat message/debug log.
    }

    /**
     * Draws text at a position local to the sidebar's own origin, scaled to match the baked label art.
     * The translate is snapped to whole pixels before the (generally non-integer) scale is applied, and
     * the built-in drop shadow is disabled -- otherwise the shadow's internal 1px offset lands on a
     * fractional post-scale pixel that doesn't line up with the main glyph, producing a doubled/ghosted
     * look under nearest-neighbor sampling.
     */
    private void drawScaledText(GuiGraphics graphics, Rect sidebar, float textScale, int localX, int localY, Component text) {
        int x = Math.round(sidebar.x() + localX * (sidebar.width() / (float) SUMMARY_PANEL_RECT.width()));
        int y = Math.round(sidebar.y() + localY * (sidebar.height() / (float) SUMMARY_PANEL_RECT.height()));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(textScale, textScale, 1f);
        graphics.drawString(this.font, text, 0, 0, 0xFFFFFFFF, false);
        graphics.pose().popPose();
    }

    private void drawChamber(GuiGraphics graphics, Layout layout, long now) {
        Rect chamber = layout.chamber();

        long stateElapsed = now - stateEnteredAtMillis;
        float flashStrength = 0f;

        // Chamber coordinates are fixed in every state -- the backing behind it is a static full-canvas
        // image, so any positional offset here would open a visible seam between the two layers.
        switch (state) {
            case OPENING -> {
                if (stateElapsed < 150L) {
                    flashStrength = stateElapsed / 150f;
                } else if (stateElapsed < 250L) {
                    flashStrength = 1f - (stateElapsed - 150L) / 100f * 0.3f;
                } else {
                    float progress = Math.min(1f, (stateElapsed - 250L) / 350f);
                    flashStrength = Math.max(0f, 0.7f * (1f - progress));
                }
            }
            default -> { }
        }

        graphics.blit(CHAMBER_BACKGROUND, chamber.x(), chamber.y(), chamber.width(), chamber.height(),
                0f, 0f, CHAMBER_RECT.width(), CHAMBER_RECT.height(), CHAMBER_RECT.width(), CHAMBER_RECT.height());
        if (chamberBanner != null) drawChamberBanner(graphics, chamber);

        if (state == State.RESULT && resultIcon != null) {
            int iconSize = Math.round(chamber.width() * 0.16f);
            graphics.pose().pushPose();
            graphics.pose().translate(chamber.centerX(), chamber.centerY(), 0);
            graphics.pose().scale(iconSize / 16f, iconSize / 16f, 1f);
            graphics.renderItem(resultIcon, -8, -8);
            graphics.pose().popPose();
        }

        if (flashStrength > 0f) {
            int glowRadius = Math.round(chamber.width() * 0.18f);
            int alpha = Math.round(Math.min(1f, flashStrength) * 160f);
            ChatFormatting tierColor = RaidTierPresentation.color(tier);
            Integer rgb = tierColor.getColor();
            int base = rgb != null ? rgb : 0xFFFFFF;
            int tinted = (alpha << 24) | (base & 0xFFFFFF);
            graphics.fill(chamber.centerX() - glowRadius, chamber.centerY() - glowRadius,
                    chamber.centerX() + glowRadius, chamber.centerY() + glowRadius, tinted);
        }

        if (state == State.RESULT) renderResult(graphics, chamber);
    }

    /**
     * A renowned boss's title across the top of the chamber.
     *
     * <p>Here rather than in the sidebar: the Boss Defeated band is one line sized for a species, and
     * "Kaelen, the Relentless" at the sidebar's text scale is wider than the whole panel. Scaled to
     * fit the chamber and drawn on a dark plate, so the gold and red read against the blue art the
     * way a nameplate reads against the sky.
     */
    private void drawChamberBanner(GuiGraphics graphics, Rect chamber) {
        int textWidth = this.font.width(chamberBanner);
        if (textWidth <= 0) return;
        float scale = Math.min(CHAMBER_BANNER_MAX_SCALE, chamber.width() * 0.8f / textWidth);
        int top = chamber.y() + Math.round(chamber.height() * CHAMBER_BANNER_TOP);
        int halfWidth = Math.round(textWidth * scale / 2f);
        int height = Math.round(this.font.lineHeight * scale);
        int pad = Math.max(2, Math.round(3 * scale));
        graphics.fill(chamber.centerX() - halfWidth - pad, top - pad,
                chamber.centerX() + halfWidth + pad, top + height, 0xA0000000);
        graphics.pose().pushPose();
        graphics.pose().translate(chamber.centerX(), top, 0);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(this.font, chamberBanner, -textWidth / 2, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    /**
     * The granted items, as a row of slots on the chamber floor.
     *
     * <p>Drawn after everything else so a tooltip sits above the art, and sized by
     * {@link RewardSlotLayout} as a share of the chamber rather than in fixed pixels -- the text
     * list this replaced stepped a constant 12px per line, which ran off the bottom of a small
     * panel and then, once that was fixed, sat on top of the Poke Ball.
     */
    private void renderResultRow(GuiGraphics graphics, Rect chamber, int mouseX, int mouseY) {
        if (result == null || !result.success()) return;
        boolean paid = resultCurrency > 0L;
        boolean points = resultPoints > 0;
        if (resultStacks.isEmpty() && !paid && !points) return;
        RewardSlotLayout slots = RewardSlotLayout.of(chamber.x(), chamber.y(), chamber.width(),
                chamber.height(), resultStacks.size() + (points ? 1 : 0) + (paid ? 1 : 0));
        if (slots == null) return;

        for (int index = 0; index < resultStacks.size(); index++) {
            drawSlotBox(graphics, slots, index, SLOT_FILL, SLOT_EDGE);
            ItemStack stack = resultStacks.get(index);
            float scale = slots.itemScale();
            int inset = Math.round((slots.cell() - RewardSlotLayout.ITEM_PIXELS * scale) / 2f);
            graphics.pose().pushPose();
            graphics.pose().translate(slots.slotX(index) + inset, slots.y() + inset, 0);
            graphics.pose().scale(scale, scale, 1.0f);
            graphics.renderItem(stack, 0, 0);
            // Vanilla's own count rendering, so a stack reads the way it does in any inventory.
            graphics.renderItemDecorations(this.font, stack, 0, 0);
            graphics.pose().popPose();
        }

        int chip = resultStacks.size();
        if (points) {
            // Raid Points are not something you can hold either, so they read as a chip too --
            // in their own colour, so a player can tell the two currencies apart at a glance.
            drawSlotBox(graphics, slots, chip, POINTS_FILL, POINTS_EDGE);
            drawFittedText(graphics, "+" + CURRENCY_FORMAT.format(resultPoints) + " RP",
                    slots.slotX(chip) + slots.cell() / 2, slots.y() + slots.cell() / 2,
                    slots.cell(), POINTS_TEXT);
            chip++;
        }
        if (paid) {
            drawSlotBox(graphics, slots, chip, CURRENCY_FILL, CURRENCY_EDGE);
            drawFittedText(graphics, "+" + CURRENCY_FORMAT.format(resultCurrency),
                    slots.slotX(chip) + slots.cell() / 2, slots.y() + slots.cell() / 2,
                    slots.cell(), CURRENCY_TEXT);
        }

        int hovered = slots.slotAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < resultStacks.size()) {
            graphics.renderTooltip(this.font, resultStacks.get(hovered), mouseX, mouseY);
        } else if (hovered >= resultStacks.size() && hovered >= 0) {
            boolean pointsChip = points && hovered == resultStacks.size();
            Component label = pointsChip
                    ? Component.literal(CURRENCY_FORMAT.format(resultPoints) + " Raid Points")
                            .withStyle(ChatFormatting.AQUA)
                    : Component.literal(CURRENCY_FORMAT.format(resultCurrency) + " CobbleDollars")
                            .withStyle(ChatFormatting.GOLD);
            if (pointsChip || paid) graphics.renderTooltip(this.font, label, mouseX, mouseY);
        }
    }

    private static void drawSlotBox(GuiGraphics graphics, RewardSlotLayout slots, int index,
                                    int fill, int edge) {
        int x = slots.slotX(index);
        int y = slots.y();
        int size = slots.cell();
        graphics.fill(x, y, x + size, y + size, fill);
        graphics.fill(x, y, x + size, y + 1, edge);
        graphics.fill(x, y + size - 1, x + size, y + size, edge);
        graphics.fill(x, y, x + 1, y + size, edge);
        graphics.fill(x + size - 1, y, x + size, y + size, edge);
    }

    /** Centred text scaled to sit inside a slot, however small the window has made one. */
    private void drawFittedText(GuiGraphics graphics, String text, int centreX, int centreY,
                                int cell, int color) {
        float scale = Math.min(1.0f, (cell - 4) / (float) Math.max(1, this.font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(centreX, centreY, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(this.font, text, -this.font.width(text) / 2, -this.font.lineHeight / 2,
                color, false);
        graphics.pose().popPose();
    }

    private void renderResult(GuiGraphics graphics, Rect chamber) {
        if (result == null || resultLines.isEmpty()) return;
        int color = result.success() ? 0xFFFFFFFF : 0xFFFF5555;
        ResultTextLayout text = ResultTextLayout.of(
                chamber.y(), chamber.height(), chamber.width(), resultLines.size());

        if (text.unscaled()) {
            int y = text.top();
            for (Component line : resultLines) {
                graphics.drawCenteredString(this.font, line, chamber.centerX(), y, color);
                y += text.step();
            }
            return;
        }

        // Six selections plus a currency line is seven rows, and at small window sizes that is
        // taller than the chamber. Scaling the block is the last resort after ResultTextLayout has
        // already slid it up towards the centre; the alternative was drawing past the art.
        graphics.pose().pushPose();
        graphics.pose().translate(chamber.centerX(), text.top(), 0);
        graphics.pose().scale(text.scale(), text.scale(), 1.0f);
        int y = 0;
        for (Component line : resultLines) {
            graphics.drawCenteredString(this.font, line, 0, y, color);
            y += text.step();
        }
        graphics.pose().popPose();
    }

    @Override
    public void removed() {
        RevealParticles.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
