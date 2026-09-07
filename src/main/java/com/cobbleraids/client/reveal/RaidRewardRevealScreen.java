package com.cobbleraids.client.reveal;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.presentation.RaidTierPresentation;
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
import net.minecraft.world.item.Item;
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

    private static final ResourceLocation FRAME_TOP = texture("outer_frame_top");
    private static final ResourceLocation FRAME_BOTTOM = texture("outer_frame_bottom");
    private static final ResourceLocation FRAME_LEFT = texture("outer_frame_left");
    private static final ResourceLocation FRAME_RIGHT = texture("outer_frame_right");
    private static final ResourceLocation SUMMARY_PANEL = texture("summary_panel");
    private static final ResourceLocation CHAMBER_BACKGROUND = texture("chamber_background");
    private static final ResourceLocation CLAIM_BUTTON = texture("claim_button");
    private static final ResourceLocation CLAIM_BUTTON_BLANK = texture("claim_button_blank");

    private static final NativeRect FRAME_TOP_RECT = new NativeRect(0, 0, 1672, 120);
    private static final NativeRect FRAME_BOTTOM_RECT = new NativeRect(0, 875, 1672, 66);
    private static final NativeRect FRAME_LEFT_RECT = new NativeRect(0, 0, 96, 941);
    private static final NativeRect FRAME_RIGHT_RECT = new NativeRect(1576, 0, 96, 941);
    private static final NativeRect SUMMARY_PANEL_RECT = new NativeRect(94, 122, 313, 731);
    private static final NativeRect CHAMBER_RECT = new NativeRect(448, 120, 1112, 657);
    private static final NativeRect BUTTON_RECT = new NativeRect(699, 787, 488, 83);
    private static final NativeRect FOOTER_RECT = new NativeRect(430, 787, 1150, 83);

    // Blanked-value row positions, local to SUMMARY_PANEL_RECT's own origin (see summary_panel.png's
    // edit history: masked from the original baked reference at these exact bands).
    private static final int SIDEBAR_LABEL_X = 8;
    private static final int SIDEBAR_ICON_LABEL_X = 85;
    private static final int VALUE_Y_BOSS = 106;
    private static final int VALUE_Y_TIER = 189;
    private static final int VALUE_Y_TIME = 271;
    private static final int VALUE_Y_DAMAGE = 404;
    private static final int VALUE_Y_PARTICIPANTS = 478;
    private static final float SIDEBAR_TEXT_SCALE = 3.0f;

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

    private RaidRewardRevealScreen(PendingRewardRevealPayload pending) {
        super(Component.literal(pending.speciesDisplayName()));
        this.pending = pending;
        this.tier = RaidRarityTier.parse(pending.rarityTier());
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
            Layout layout = screen.computeLayout();
            RevealParticles.spawnBurst(layout.chamber().centerX(), layout.chamber().centerY());
        }
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
        Layout layout = computeLayout();
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
                        Component.literal(choiceId), button -> onChoose(choiceId),
                        CLAIM_BUTTON_BLANK, 488, 83));
            }
        } else if (state == State.RESULT) {
            int buttonWidth = Math.round(BUTTON_RECT.width() * layout.scale());
            int buttonHeight = Math.round(BUTTON_RECT.height() * layout.scale());
            this.addRenderableWidget(new TexturedButton(layout.footer().centerX() - buttonWidth / 2, layout.footer().y,
                    buttonWidth, buttonHeight, Component.empty(), button -> this.onClose(),
                    CLAIM_BUTTON, 488, 83));
        }
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
            result = pendingResult;
            state = State.RESULT;
            stateEnteredAtMillis = now;
            clearWidgets();
            init();
        }

        this.renderTransparentBackground(graphics);

        Layout layout = computeLayout();
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

        RevealParticles.renderAndCull(graphics);
    }

    private void drawFrame(GuiGraphics graphics, Layout layout) {
        blitNative(graphics, FRAME_TOP, layout.toScreen(FRAME_TOP_RECT), FRAME_TOP_RECT);
        blitNative(graphics, FRAME_BOTTOM, layout.toScreen(FRAME_BOTTOM_RECT), FRAME_BOTTOM_RECT);
        blitNative(graphics, FRAME_LEFT, layout.toScreen(FRAME_LEFT_RECT), FRAME_LEFT_RECT);
        blitNative(graphics, FRAME_RIGHT, layout.toScreen(FRAME_RIGHT_RECT), FRAME_RIGHT_RECT);
        blitNative(graphics, SUMMARY_PANEL, layout.sidebar(), SUMMARY_PANEL_RECT);
    }

    private static void blitNative(GuiGraphics graphics, ResourceLocation texture, Rect dest, NativeRect native_) {
        graphics.blit(texture, dest.x(), dest.y(), dest.width(), dest.height(),
                0f, 0f, native_.width(), native_.height(), native_.width(), native_.height());
    }

    private void drawSidebar(GuiGraphics graphics, Layout layout) {
        Rect sidebar = layout.sidebar();
        float textScale = layout.scale() * SIDEBAR_TEXT_SCALE;

        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_BOSS,
                Component.literal(pending.speciesDisplayName()).withStyle(RaidTierPresentation.color(tier)));
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_TIER,
                Component.literal(tier.displayName()).withStyle(RaidTierPresentation.color(tier)));

        long elapsedSeconds = pending.elapsedCombatTicks() / 20L;
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_LABEL_X, VALUE_Y_TIME,
                Component.literal(String.format(Locale.ROOT, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)));

        drawScaledText(graphics, sidebar, textScale, SIDEBAR_ICON_LABEL_X, VALUE_Y_DAMAGE,
                Component.literal(String.format(Locale.ROOT, "%.1f%%", pending.contributionPercentage())));
        drawScaledText(graphics, sidebar, textScale, SIDEBAR_ICON_LABEL_X, VALUE_Y_PARTICIPANTS,
                Component.literal(Integer.toString(pending.participantCount())));
        // Contribution bonus rolls have no row in this layout (the art's "Support Actions" slot was
        // removed rather than repurposed) -- still shown in the existing claim chat message/debug log.
    }

    /** Draws text at a position local to the sidebar's own origin, scaled to match the baked label art. */
    private void drawScaledText(GuiGraphics graphics, Rect sidebar, float textScale, int localX, int localY, Component text) {
        graphics.pose().pushPose();
        graphics.pose().translate(sidebar.x() + localX * (sidebar.width() / (float) SUMMARY_PANEL_RECT.width()),
                sidebar.y() + localY * (sidebar.height() / (float) SUMMARY_PANEL_RECT.height()), 0);
        graphics.pose().scale(textScale, textScale, 1f);
        graphics.drawString(this.font, text, 0, 0, 0xFFFFFFFF);
        graphics.pose().popPose();
    }

    private void drawChamber(GuiGraphics graphics, Layout layout, long now) {
        Rect chamber = layout.chamber();

        float sinceOpenedSeconds = (now - openedAtMillis) / 1000f;
        long stateElapsed = now - stateEnteredAtMillis;
        float t = stateElapsed / 1000f;

        float offsetX = 0f;
        float offsetY = 0f;
        float flashStrength = 0f;

        switch (state) {
            case CHOOSING -> offsetY = (float) Math.sin(sinceOpenedSeconds * 2.0) * 2f;
            case WAITING -> offsetX = (float) Math.sin(t * 18.0) * 2f;
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

        int chamberX = chamber.x() + Math.round(offsetX);
        int chamberY = chamber.y() + Math.round(offsetY);
        graphics.blit(CHAMBER_BACKGROUND, chamberX, chamberY, chamber.width(), chamber.height(),
                0f, 0f, CHAMBER_RECT.width(), CHAMBER_RECT.height(), CHAMBER_RECT.width(), CHAMBER_RECT.height());

        if (state == State.RESULT) {
            int iconSize = Math.round(chamber.width() * 0.16f);
            ItemStack displayed = resultIcon();
            graphics.pose().pushPose();
            graphics.pose().translate(chamber.centerX(), chamber.centerY(), 0);
            graphics.pose().scale(iconSize / 16f, iconSize / 16f, 1f);
            graphics.renderItem(displayed, -8, -8);
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

    private ItemStack resultIcon() {
        if (result == null || !result.success() || result.granted().isEmpty()) return new ItemStack(ballItem());
        Item resolved = BuiltInRegistries.ITEM.get(result.granted().get(0).item());
        return new ItemStack(resolved);
    }

    private void renderResult(GuiGraphics graphics, Rect chamber) {
        if (result == null) return;
        int y = chamber.centerY() + Math.round(chamber.width() * 0.1f);
        if (!result.success()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Something went wrong. Check chat for details."), chamber.centerX(), y, 0xFFFF5555);
            return;
        }
        for (RewardItemPayload item : result.granted()) {
            Item resolved = BuiltInRegistries.ITEM.get(item.item());
            Component line = Component.literal(new ItemStack(resolved).getHoverName().getString() + " x" + item.amount());
            graphics.drawCenteredString(this.font, line, chamber.centerX(), y, 0xFFFFFFFF);
            y += 12;
        }
    }

    private static Item ballItem() {
        return BuiltInRegistries.ITEM.get(BALL_ITEM);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
