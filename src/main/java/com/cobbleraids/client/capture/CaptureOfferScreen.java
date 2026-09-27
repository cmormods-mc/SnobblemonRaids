package com.cobbleraids.client.capture;

import com.cobbleraids.network.CaptureChoicePayload;
import com.cobbleraids.network.CaptureOfferPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Raid Capture Protocol's choice prompt: attempt the timing minigame for a chance at the boss's
 * species, or keep this raid's Raid Points, which have already been computed (and already shown on
 * the ordinary reward screen the player just came from) by the time this ever opens. Plain vanilla
 * widgets rather than bespoke art -- no capture-chamber texture exists yet, the same way the Trophy
 * Gallery shipped its data/wiring layer before its real art followed later. Swap in real art by
 * replacing this screen's rendering, not its logic.
 */
public final class CaptureOfferScreen extends Screen {
    private final CaptureOfferPayload offer;

    private CaptureOfferScreen(CaptureOfferPayload offer) {
        super(Component.literal("Raid Capture Protocol"));
        this.offer = offer;
    }

    public static void openFor(CaptureOfferPayload payload) {
        Minecraft.getInstance().setScreen(new CaptureOfferScreen(payload));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Attempt Capture"), button -> onChoice(true))
                .bounds(centerX - 100, centerY + 20, 200, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.literal("Keep " + offer.raidPointsAtStake() + " RP"), button -> onChoice(false))
                .bounds(centerX - 100, centerY + 46, 200, 20).build());
    }

    private void onChoice(boolean attempt) {
        ClientPlayNetworking.send(new CaptureChoicePayload(offer.raidId(), attempt));
        if (attempt) {
            CaptureMinigameScreen.openFor(offer);
        } else {
            this.onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render() (invoked below via super.render()) unconditionally calls this method again
        // after our own renderTransparentBackground() call -- overriding it to a no-op stops that
        // second, vanilla-dispatched call from re-triggering the blurring renderBackground behavior.
        // See reference_screen_background_blur: this exact bug has shipped twice already.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(graphics);

        int centerX = this.width / 2;
        int top = this.height / 2 - 60;
        String title = (offer.shiny() ? "Shiny " : "") + offer.speciesDisplayName();
        graphics.drawCenteredString(this.font, title, centerX, top, 0xFFFFFF);
        graphics.drawCenteredString(this.font, offer.tier() + " raid capture opportunity", centerX, top + 14, 0xAAAAAA);
        graphics.drawCenteredString(this.font,
                "Attempt to catch it, risking the " + offer.raidPointsAtStake() + " RP you just earned.",
                centerX, top + 30, 0xCCCCCC);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
