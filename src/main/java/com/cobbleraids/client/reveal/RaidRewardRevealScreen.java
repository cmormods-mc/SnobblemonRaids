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
 * Presentation is a flat-panel "hologram terminal" layout (title bar + summary sidebar + viewport +
 * button bar), drawn with plain fill()/renderOutline() placeholders -- there is no bundled texture
 * yet. NineSliceRenderer (com.cobbleraids.client.gui) is ready to slice a real frame texture in here
 * once one exists; swapping the flat panels for it is a small, isolated follow-up.
 */
public final class RaidRewardRevealScreen extends Screen {
    private enum State { CHOOSING, WAITING, OPENING, RESULT }

    private static final ResourceLocation BALL_ITEM = ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball");
    private static final long OPENING_DURATION_MILLIS = 600L;

    private static final int COLOR_PANEL_BG = 0xE0102A43;
    private static final int COLOR_TITLE_BG = 0xFF1B3A5C;
    private static final int COLOR_SIDEBAR_BG = 0xFF15304C;
    private static final int COLOR_VIEWPORT_BG = 0xFF0D2138;
    private static final int COLOR_BOTTOM_BG = 0xFF1B3A5C;
    private static final int COLOR_OUTLINE = 0xFF5AC8FA;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MUTED = 0xFFAACCE0;

    private record Rect(int x, int y, int width, int height) {
        int centerX() { return x + width / 2; }
        int centerY() { return y + height / 2; }
        int right() { return x + width; }
        int bottom() { return y + height; }
    }

    private record Layout(Rect panel, Rect title, Rect sidebar, Rect viewport, Rect bottomBar) {}

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
            RevealParticles.spawnBurst(layout.viewport().centerX(), layout.viewport().centerY());
        }
    }

    private Layout computeLayout() {
        int availableWidth = this.width - 24;
        int availableHeight = this.height - 24;
        float designRatio = 16f / 10f;
        int panelWidth = Math.min(400, Math.max(240, availableWidth));
        int panelHeight = Math.round(panelWidth / designRatio);
        if (panelHeight > availableHeight) {
            panelHeight = Math.max(160, availableHeight);
            panelWidth = Math.round(panelHeight * designRatio);
        }
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        Rect panel = new Rect(panelX, panelY, panelWidth, panelHeight);

        int titleHeight = 20;
        int bottomHeight = 30;
        int sidebarWidth = Math.round(panelWidth * 0.32f);

        Rect title = new Rect(panelX, panelY, panelWidth, titleHeight);
        Rect bottomBar = new Rect(panelX, panelY + panelHeight - bottomHeight, panelWidth, bottomHeight);
        int bodyY = panelY + titleHeight;
        int bodyHeight = panelHeight - titleHeight - bottomHeight;
        Rect sidebar = new Rect(panelX, bodyY, sidebarWidth, bodyHeight);
        Rect viewport = new Rect(panelX + sidebarWidth, bodyY, panelWidth - sidebarWidth, bodyHeight);
        return new Layout(panel, title, sidebar, viewport, bottomBar);
    }

    @Override
    protected void init() {
        Layout layout = computeLayout();
        if (state == State.CHOOSING) {
            List<String> choices = pending.choiceIds();
            int buttonWidth = Math.min(120, Math.max(60, (layout.bottomBar().width() - 16) / Math.max(1, choices.size()) - 8));
            int totalWidth = choices.size() * buttonWidth + Math.max(0, choices.size() - 1) * 8;
            int startX = layout.bottomBar().centerX() - totalWidth / 2;
            int y = layout.bottomBar().centerY() - 10;
            int index = 0;
            for (String choiceId : choices) {
                int x = startX + index * (buttonWidth + 8);
                index++;
                this.addRenderableWidget(Button.builder(Component.literal(choiceId), button -> onChoose(choiceId))
                        .bounds(x, y, buttonWidth, 20)
                        .build());
            }
        } else if (state == State.RESULT) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                    .bounds(layout.bottomBar().centerX() - 75, layout.bottomBar().centerY() - 10, 150, 20)
                    .build());
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (state == State.OPENING && now - stateEnteredAtMillis >= OPENING_DURATION_MILLIS) {
            result = pendingResult;
            state = State.RESULT;
            stateEnteredAtMillis = now;
            clearWidgets();
            init();
        }

        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        Layout layout = computeLayout();
        drawFrame(graphics, layout);
        drawSidebar(graphics, layout.sidebar());
        drawViewport(graphics, layout.viewport(), now);

        super.render(graphics, mouseX, mouseY, partialTick);

        switch (state) {
            case WAITING -> graphics.drawCenteredString(this.font, Component.literal("Revealing..."),
                    layout.bottomBar().centerX(), layout.bottomBar().centerY() - 4, ChatFormatting.YELLOW.getColor());
            case OPENING -> graphics.drawCenteredString(this.font, Component.literal("..."),
                    layout.bottomBar().centerX(), layout.bottomBar().centerY() - 4, ChatFormatting.YELLOW.getColor());
            default -> { }
        }

        RevealParticles.renderAndCull(graphics);
    }

    private void drawFrame(GuiGraphics graphics, Layout layout) {
        Rect panel = layout.panel();
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), COLOR_PANEL_BG);
        graphics.renderOutline(panel.x(), panel.y(), panel.width(), panel.height(), COLOR_OUTLINE);

        Rect title = layout.title();
        graphics.fill(title.x(), title.y(), title.right(), title.bottom(), COLOR_TITLE_BG);
        graphics.drawString(this.font, Component.literal("Raid Rewards"), title.x() + 8, title.y() + 6, COLOR_TEXT);

        Rect bottomBar = layout.bottomBar();
        graphics.fill(bottomBar.x(), bottomBar.y(), bottomBar.right(), bottomBar.bottom(), COLOR_BOTTOM_BG);

        Rect sidebar = layout.sidebar();
        graphics.fill(sidebar.x(), sidebar.y(), sidebar.right(), sidebar.bottom(), COLOR_SIDEBAR_BG);
        Rect viewport = layout.viewport();
        graphics.fill(viewport.x(), viewport.y(), viewport.right(), viewport.bottom(), COLOR_VIEWPORT_BG);
    }

    private void drawSidebar(GuiGraphics graphics, Rect sidebar) {
        int x = sidebar.x() + 6;
        int y = sidebar.y() + 6;
        int lineHeight = 11;

        y = drawSidebarHeader(graphics, x, y, "RAID SUMMARY");
        y = drawSidebarRow(graphics, x, y, "Boss Defeated",
                Component.literal(pending.speciesDisplayName()).withStyle(RaidTierPresentation.color(tier)));
        y = drawSidebarRow(graphics, x, y, "Raid Tier",
                Component.literal(tier.displayName()).withStyle(RaidTierPresentation.color(tier)));
        long elapsedSeconds = pending.elapsedCombatTicks() / 20L;
        y = drawSidebarRow(graphics, x, y, "Completion Time",
                Component.literal(String.format(Locale.ROOT, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)));
        y += lineHeight / 2;

        y = drawSidebarHeader(graphics, x, y, "YOUR CONTRIBUTION");
        y = drawSidebarRow(graphics, x, y, "Damage Dealt",
                Component.literal(String.format(Locale.ROOT, "%.1f%%", pending.contributionPercentage())));
        y = drawSidebarRow(graphics, x, y, "Bonus Rolls", Component.literal(Integer.toString(pending.contributionBonusRolls())));
        drawSidebarRow(graphics, x, y, "Participants", Component.literal(Integer.toString(pending.participantCount())));
    }

    private int drawSidebarHeader(GuiGraphics graphics, int x, int y, String label) {
        graphics.drawString(this.font, Component.literal(label).withStyle(ChatFormatting.GOLD), x, y, COLOR_TEXT_MUTED);
        return y + 12;
    }

    private int drawSidebarRow(GuiGraphics graphics, int x, int y, String label, Component value) {
        graphics.drawString(this.font, Component.literal(label), x, y, COLOR_TEXT_MUTED);
        graphics.drawString(this.font, value, x, y + 9, COLOR_TEXT);
        return y + 20;
    }

    private void drawViewport(GuiGraphics graphics, Rect viewport, long now) {
        float sinceOpenedSeconds = (now - openedAtMillis) / 1000f;
        long stateElapsed = now - stateEnteredAtMillis;
        float t = stateElapsed / 1000f;

        float offsetX = 0f;
        float offsetY = 0f;
        float flashStrength = 0f;

        switch (state) {
            case CHOOSING -> offsetY = (float) Math.sin(sinceOpenedSeconds * 2.0) * 3f;
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

        int iconSize = 32;
        int iconX = Math.round(viewport.centerX() + offsetX);
        int iconY = Math.round(viewport.centerY() + offsetY) - (state == State.RESULT ? 12 : 0);

        ItemStack displayed = state == State.RESULT ? resultIcon() : new ItemStack(ballItem());
        graphics.pose().pushPose();
        graphics.pose().translate(iconX, iconY, 0);
        graphics.pose().scale(iconSize / 16f, iconSize / 16f, 1f);
        graphics.renderItem(displayed, -8, -8);
        graphics.pose().popPose();

        if (flashStrength > 0f) {
            int glowRadius = 30;
            int alpha = Math.round(Math.min(1f, flashStrength) * 160f);
            ChatFormatting tierColor = RaidTierPresentation.color(tier);
            Integer rgb = tierColor.getColor();
            int base = rgb != null ? rgb : 0xFFFFFF;
            int tinted = (alpha << 24) | (base & 0xFFFFFF);
            graphics.fill(viewport.centerX() - glowRadius, viewport.centerY() - glowRadius,
                    viewport.centerX() + glowRadius, viewport.centerY() + glowRadius, tinted);
        }

        switch (state) {
            case CHOOSING -> graphics.drawCenteredString(this.font, Component.literal("Choose your reward"),
                    viewport.centerX(), viewport.bottom() - 14, COLOR_TEXT);
            case RESULT -> renderResult(graphics, viewport);
            default -> { }
        }
    }

    private ItemStack resultIcon() {
        if (result == null || !result.success() || result.granted().isEmpty()) return new ItemStack(ballItem());
        Item resolved = BuiltInRegistries.ITEM.get(result.granted().get(0).item());
        return new ItemStack(resolved);
    }

    private void renderResult(GuiGraphics graphics, Rect viewport) {
        if (result == null) return;
        int y = viewport.centerY() + 10;
        if (!result.success()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Something went wrong. Check chat for details."), viewport.centerX(), y, 0xFF5555);
            return;
        }
        for (RewardItemPayload item : result.granted()) {
            Item resolved = BuiltInRegistries.ITEM.get(item.item());
            Component line = Component.literal(new ItemStack(resolved).getHoverName().getString() + " x" + item.amount());
            graphics.drawCenteredString(this.font, line, viewport.centerX(), y, COLOR_TEXT);
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
