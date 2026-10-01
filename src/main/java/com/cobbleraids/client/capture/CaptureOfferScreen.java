package com.cobbleraids.client.capture;

import com.cobbleraids.network.CaptureChoicePayload;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Raid Capture Protocol's choice prompt: attempt the timing minigame for a chance at the boss's
 * species, or keep this raid's Raid Points, which have already been computed (and already shown on
 * the ordinary reward screen the player just came from) by the time this ever opens.
 */
public final class CaptureOfferScreen extends Screen {
    private final CaptureOfferPayload offer;
    private final CaptureDetailsPayload details;
    private final long openedAtNanos = System.nanoTime();
    private CaptureUiLayout layout;
    private boolean chosen;

    private CaptureOfferScreen(CaptureOfferPayload offer) {
        super(Component.literal("Raid Capture"));
        this.offer = offer;
        this.details = CaptureDetailsCache.take(offer.raidId());
    }

    public static void openFor(CaptureOfferPayload offer) {
        Minecraft.getInstance().setScreen(new CaptureOfferScreen(offer));
    }

    @Override
    protected void init() {
        clearWidgets();
        layout = CaptureUiLayout.fit(width, height);
        int buttonWidth = (layout.width() - 34) / 2;
        addRenderableWidget(new CaptureUi.Action(font, layout.x() + 12, layout.buttonY(), buttonWidth,
                "Attempt Capture", () -> choose(true)));
        addRenderableWidget(new CaptureUi.Action(font, layout.x() + 22 + buttonWidth, layout.buttonY(), buttonWidth,
                "Keep " + offer.raidPointsAtStake() + " RP", () -> choose(false)));
    }

    private void choose(boolean attempt) {
        if (chosen) return;
        chosen = true;
        ClientPlayNetworking.send(new CaptureChoicePayload(offer.raidId(), attempt));
        if (attempt) {
            CaptureMinigameScreen.openFor(offer, details);
        } else {
            super.onClose();
        }
    }

    @Override
    public void onClose() {
        choose(false);
    }

    @Override
    public void tick() {
        // The server independently expires this choice on its own tick sweep regardless of what the
        // client ever sends (RaidCaptureSessionService.tick), so closing here with no packet is safe:
        // it just lets the screen catch up to a decision the server would make on its own either way.
        if (remaining() == 0 && !chosen) {
            chosen = true;
            super.onClose();
        }
    }

    private int remaining() {
        long elapsedSeconds = (System.nanoTime() - openedAtNanos) / 1_000_000_000L;
        return Math.max(0, offer.choiceSecondsRemaining() - (int) elapsedSeconds);
    }

    @Override
    public void removed() {
        CaptureArt.release();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render() (invoked below via super.render()) unconditionally calls this method again
        // after our own renderTransparentBackground() call -- overriding it to a no-op stops that
        // second, vanilla-dispatched call from re-triggering the blurring renderBackground behavior.
        // See reference_screen_background_blur: this exact bug has shipped twice already.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        CaptureUi.frame(g, font, layout, "RAID CAPTURE");
        CaptureUi.chamber(g, font, layout, offer, details, partialTick, false, 0, false);

        int x = layout.infoX() + 9, y = layout.contentY() + 10, w = layout.infoWidth() - 18;
        CaptureUi.text(g, font, (offer.shiny() ? "Shiny " : "") + offer.speciesDisplayName(), x, y, w, CaptureUi.WHITE);
        CaptureUi.text(g, font, offer.tier().toUpperCase(Locale.ROOT), x, y + 15, w, CaptureUi.CYAN);
        CaptureUi.text(g, font, offer.raidPointsAtStake() + " RP earned", x, y + 31, w, CaptureUi.WHITE);

        String odds = details == null ? "Server configured"
                : CaptureUi.pct(details.base()) + " - " + CaptureUi.pct(oddsCeiling());
        if (layout.contentHeight() >= 100) {
            CaptureUi.text(g, font, "Capture chance", x, y + 51, w, CaptureUi.MUTED);
            CaptureUi.text(g, font, odds, x, y + 64, w, CaptureUi.CYAN);
        } else if (details != null) {
            CaptureUi.text(g, font, odds, x, y + 47, w, CaptureUi.CYAN);
        }
        if (layout.contentHeight() >= 155) {
            CaptureUi.wrap(g, font, "Three pulses. One throw. Timing improves your odds.", x, y + 89, w, CaptureUi.WHITE);
        }
        if (layout.contentHeight() >= 210) {
            CaptureArt.scanner(g, font, x, y + 125, w, layout.contentHeight() - 145, 0);
        }
        CaptureArt.lamps(g, font, layout, "ECHO READY", 0);

        int messageY = layout.y() + layout.height() - 99;
        CaptureUi.wrap(g, font, "Caught: Pokemon replaces this raid's RP. Escaped: keep all "
                + offer.raidPointsAtStake() + " RP.", layout.x() + 16, messageY, layout.width() - 32, CaptureUi.WHITE);
        CaptureUi.text(g, font, "Other loot is safe. Choose within " + remaining() + "s.",
                layout.x() + 16, layout.y() + layout.height() - 55, layout.width() - 32, CaptureUi.CYAN);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private double oddsCeiling() {
        return Math.min(1, details.base() + details.stabilizationCap() + details.throwCap());
    }
}
