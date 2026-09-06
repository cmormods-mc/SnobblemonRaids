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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Native raid-reward reveal screen: pick a choice, wait for the server's roll, see what was actually
 * granted. Placeholder presentation (a scaled 2D ball icon) -- a real 3D model is later phase polish,
 * not part of this MVP. The server remains fully authoritative: this screen only ever sends a
 * choiceId and only ever displays what RewardResultPayload reports back.
 */
public final class RaidRewardRevealScreen extends Screen {
    private enum State { CHOOSING, WAITING, RESULT }

    private static final ResourceLocation BALL_ITEM = ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball");

    private final PendingRewardRevealPayload pending;
    private final long openedAtMillis = System.currentTimeMillis();
    private State state = State.CHOOSING;
    private RewardResultPayload result;

    private RaidRewardRevealScreen(PendingRewardRevealPayload pending) {
        super(Component.literal(pending.speciesDisplayName()));
        this.pending = pending;
    }

    public static void openFor(PendingRewardRevealPayload payload) {
        Minecraft.getInstance().setScreen(new RaidRewardRevealScreen(payload));
    }

    /** No-op if the current screen isn't the matching reveal screen -- e.g. the player already closed it. */
    public static void applyResult(RewardResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof RaidRewardRevealScreen screen
                && screen.pending.raidId().equals(payload.raidId())) {
            screen.result = payload;
            screen.state = State.RESULT;
            screen.clearWidgets();
            screen.init();
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
        this.clearWidgets();
        this.init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2 - 30;

        RaidRarityTier tier = RaidRarityTier.parse(pending.rarityTier());
        Component title = Component.literal(pending.speciesDisplayName()).withStyle(RaidTierPresentation.color(tier));
        graphics.drawCenteredString(this.font, title, centerX, 20, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(String.format(Locale.ROOT,
                "Contribution: %.1f%%", pending.contributionPercentage())), centerX, 32, 0xAAAAAA);

        long elapsed = System.currentTimeMillis() - openedAtMillis;
        float scale = 0.8f + 0.2f * Math.min(1f, elapsed / 300f);

        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0);
        graphics.pose().scale(scale, scale, 1f);
        graphics.renderItem(new ItemStack(ballItem()), -8, -8);
        graphics.pose().popPose();

        switch (state) {
            case CHOOSING -> graphics.drawCenteredString(this.font,
                    Component.literal("Choose your reward"), centerX, centerY + 40, 0xFFFFFF);
            case WAITING -> graphics.drawCenteredString(this.font,
                    Component.literal("Revealing..."), centerX, centerY + 40, ChatFormatting.YELLOW.getColor());
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

    private static Item ballItem() {
        return BuiltInRegistries.ITEM.get(BALL_ITEM);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
