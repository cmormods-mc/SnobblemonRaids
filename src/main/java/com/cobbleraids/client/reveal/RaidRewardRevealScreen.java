package com.cobbleraids.client.reveal;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * Native raid-reward reveal screen: pick a choice, wait for the server's roll, watch the ball open,
 * see what was actually granted. The server remains fully authoritative: this screen only ever sends
 * a choiceId and only ever displays what RewardResultPayload reports back.
 */
public final class RaidRewardRevealScreen extends Screen {
    private enum State { CHOOSING, WAITING, OPENING, RESULT }

    private static final float BASE_RADIUS_PX = 40f;
    private static final long OPENING_DURATION_MILLIS = 600L;

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
            RevealParticles.spawnBurst(screen.width / 2f, screen.height / 2f - 30f);
        }
    }

    @Override
    protected void init() {
        if (state == State.CHOOSING) {
            int y = this.height / 2 + 10;
            int index = 0;
            for (String choiceId : pending.choiceIds()) {
                int row = index++;
                this.addRenderableWidget(Button.builder(Component.literal(choiceId), button -> onChoose(choiceId))
                        .bounds(this.width / 2 - 75, y + row * 24, 150, 20)
                        .build());
            }
        } else if (state == State.RESULT) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                    .bounds(this.width / 2 - 75, this.height - 40, 150, 20)
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
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2 - 30;

        Component title = Component.literal(pending.speciesDisplayName()).withStyle(RaidTierPresentation.color(tier));
        graphics.drawCenteredString(this.font, title, centerX, 20, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(String.format(Locale.ROOT,
                "Contribution: %.1f%%", pending.contributionPercentage())), centerX, 32, 0xAAAAAA);

        float sinceOpenedSeconds = (now - openedAtMillis) / 1000f;
        float growIn = 0.8f + 0.2f * Math.min(1f, sinceOpenedSeconds / 0.3f);
        float radiusPx = BASE_RADIUS_PX * growIn;

        long stateElapsed = now - stateEnteredAtMillis;
        float t = stateElapsed / 1000f;

        Quaternionf rotation = new Quaternionf();
        float bobPx = 0f;
        float openSeparation = 0f;
        float flashStrength = 0f;

        switch (state) {
            case CHOOSING -> {
                rotation.rotateY(sinceOpenedSeconds * 0.8f);
                bobPx = (float) Math.sin(sinceOpenedSeconds * 2.0) * 3f;
            }
            case WAITING -> rotation.rotateY((float) Math.sin(t * 18.0) * 0.12f)
                    .rotateX((float) Math.sin(t * 13.0 + 1.3) * 0.08f);
            case OPENING -> {
                if (stateElapsed < 150L) {
                    float decay = 1f - (stateElapsed / 150f);
                    rotation.rotateY((float) Math.sin(t * 24.0) * 0.16f * decay)
                            .rotateX((float) Math.sin(t * 17.0) * 0.10f * decay);
                    flashStrength = stateElapsed / 150f;
                } else if (stateElapsed < 250L) {
                    openSeparation = (stateElapsed - 150L) / 100f * 0.15f;
                    flashStrength = 1f - (stateElapsed - 150L) / 100f * 0.3f;
                } else {
                    float progress = Math.min(1f, (stateElapsed - 250L) / 350f);
                    rotation.rotateX(progress * 0.35f);
                    openSeparation = 0.15f + progress * 0.85f;
                    flashStrength = Math.max(0f, 0.7f * (1f - progress));
                }
            }
            case RESULT -> openSeparation = 1f;
        }

        int flashColor = PokeBallMesh.argbFromChatFormatting(RaidTierPresentation.color(tier), 255);
        PokeBallMesh.render(graphics, centerX, Math.round(centerY + bobPx), radiusPx, rotation,
                openSeparation, flashColor, flashStrength);
        RevealParticles.renderAndCull(graphics);

        switch (state) {
            case CHOOSING -> graphics.drawCenteredString(this.font,
                    Component.literal("Choose your reward"), centerX, centerY + 40, 0xFFFFFF);
            case WAITING -> graphics.drawCenteredString(this.font,
                    Component.literal("Revealing..."), centerX, centerY + 40, ChatFormatting.YELLOW.getColor());
            case OPENING -> graphics.drawCenteredString(this.font,
                    Component.literal("..."), centerX, centerY + 40, ChatFormatting.YELLOW.getColor());
            case RESULT -> renderResult(graphics, centerX, centerY + 40);
        }
    }

    private void renderResult(GuiGraphics graphics, int centerX, int startY) {
        if (result == null) return;
        if (!result.success()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Something went wrong. Check chat for details."), centerX, startY, 0xFF5555);
            return;
        }
        int y = startY;
        for (RewardItemPayload item : result.granted()) {
            Item resolved = BuiltInRegistries.ITEM.get(item.item());
            Component line = Component.literal(new ItemStack(resolved).getHoverName().getString() + " x" + item.amount());
            graphics.drawCenteredString(this.font, line, centerX, y, 0xFFFFFF);
            y += 12;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
