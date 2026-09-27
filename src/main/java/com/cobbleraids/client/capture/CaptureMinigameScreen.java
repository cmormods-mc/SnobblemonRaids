package com.cobbleraids.client.capture;

import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseInputPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.cobbleraids.network.CaptureThrowInputPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Raid Capture Protocol's timing minigame: three stabilization pulses, then a throw. The server
 * scores every input off its own clock and rolls exactly once -- this screen only ever animates
 * toward an already-known result once {@link CaptureResultPayload} arrives; it never decides an
 * outcome locally. Plain vanilla widgets, no bespoke art yet; see {@link CaptureOfferScreen}'s note.
 */
public final class CaptureMinigameScreen extends Screen {
    private enum Phase { PULSE, THROW, RESULT }

    private final CaptureOfferPayload offer;
    private CaptureMinigameLayout layout;

    private Phase phase = Phase.PULSE;
    private int pulseIndex = 0;
    private final double[] pulseScores = new double[3];
    private long stepStartedAtMillis = System.currentTimeMillis();
    private boolean waitingForServer = false;
    private CaptureResultPayload result;
    private Button actionButton;

    private CaptureMinigameScreen(CaptureOfferPayload offer) {
        super(Component.literal("Raid Capture Protocol"));
        this.offer = offer;
    }

    public static void openFor(CaptureOfferPayload offer) {
        Minecraft.getInstance().setScreen(new CaptureMinigameScreen(offer));
    }

    /** No-op if the current screen isn't the matching minigame, or the pulse isn't the one expected. */
    public static void applyPulseResult(CapturePulseResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof CaptureMinigameScreen screen
                && screen.offer.raidId().equals(payload.raidId())
                && screen.phase == Phase.PULSE
                && payload.pulseIndex() == screen.pulseIndex) {
            screen.pulseScores[screen.pulseIndex] = payload.score();
            screen.waitingForServer = false;
            if (screen.pulseIndex == 2) {
                screen.phase = Phase.THROW;
            } else {
                screen.pulseIndex++;
            }
            screen.stepStartedAtMillis = System.currentTimeMillis();
            screen.updateActionButton();
        }
    }

    /** No-op if the current screen isn't the matching minigame -- e.g. the player already closed it. */
    public static void applyResult(CaptureResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof CaptureMinigameScreen screen
                && screen.offer.raidId().equals(payload.raidId())) {
            screen.result = payload;
            screen.phase = Phase.RESULT;
            screen.waitingForServer = false;
            screen.clearWidgets();
            screen.init();
        }
    }

    @Override
    protected void init() {
        this.layout = CaptureMinigameLayout.fit(this.width, this.height);
        if (phase == Phase.RESULT) {
            this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
                    .bounds(layout.panelX() + layout.panelWidth() / 2 - 50, layout.trackY(), 100, 20).build());
        } else {
            this.actionButton = this.addRenderableWidget(Button.builder(Component.literal(""), button -> onAction())
                    .bounds(layout.trackCenterX() - 50, layout.buttonY(), 100, 20).build());
            updateActionButton();
        }
    }

    private void updateActionButton() {
        if (actionButton == null) return;
        actionButton.setMessage(Component.literal(phase == Phase.THROW ? "Throw!" : "Press!"));
    }

    private void onAction() {
        if (waitingForServer || phase == Phase.RESULT) return;
        waitingForServer = true;
        if (phase == Phase.PULSE) {
            ClientPlayNetworking.send(new CapturePulseInputPayload(offer.raidId(), pulseIndex));
        } else {
            ClientPlayNetworking.send(new CaptureThrowInputPayload(offer.raidId()));
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
        CaptureMinigameLayout layout = this.layout;

        graphics.fill(layout.panelX(), layout.panelY(), layout.panelX() + layout.panelWidth(),
                layout.panelY() + layout.panelHeight(), 0xC0101010);

        int centerX = layout.panelX() + layout.panelWidth() / 2;
        graphics.drawCenteredString(this.font, phaseTitle(), centerX, layout.titleY(), 0xFFFFFF);
        graphics.drawCenteredString(this.font, phaseSubtitle(), centerX, layout.subtitleY(), 0xAAAAAA);

        if (phase != Phase.RESULT) {
            drawTrack(graphics, layout);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTrack(GuiGraphics graphics, CaptureMinigameLayout layout) {
        int trackLeft = layout.trackX();
        int trackTop = layout.trackY();
        int trackRight = trackLeft + layout.trackWidth();
        int trackBottom = trackTop + layout.trackHeight();
        graphics.fill(trackLeft, trackTop, trackRight, trackBottom, 0xFF303030);

        int travelDurationMs = phase == Phase.THROW ? offer.throwTravelDurationMs() : offer.pulseTravelDurationMs();
        int goodZoneWidthMs = phase == Phase.THROW ? offer.throwGoodZoneWidthMs() : offer.pulseGoodZoneWidthMs();
        int perfectZoneWidthMs = phase == Phase.THROW ? offer.throwPerfectZoneWidthMs() : offer.pulsePerfectZoneWidthMs();

        int[] good = layout.zoneBounds(goodZoneWidthMs, travelDurationMs);
        graphics.fill(good[0], trackTop, good[0] + good[1], trackBottom, 0x80FFD400);
        int[] perfect = layout.zoneBounds(perfectZoneWidthMs, travelDurationMs);
        graphics.fill(perfect[0], trackTop, perfect[0] + perfect[1], trackBottom, 0xC000FF66);

        long elapsed = Math.max(0, System.currentTimeMillis() - stepStartedAtMillis);
        int indicatorX = layout.indicatorX(bounceProgress(elapsed, travelDurationMs));
        graphics.fill(indicatorX - 1, trackTop - 4, indicatorX + 1, trackBottom + 4, 0xFFFFFFFF);
    }

    /**
     * The indicator sweeps end-to-end every {@code travelDurationMs}, then bounces back, for as long
     * as the player takes to press -- it never simply stops at the far side with nothing left to do.
     * The server scores a press the same way: against whichever crossing of the track's fixed center
     * it actually landed nearest, not against ever-growing absolute elapsed time (see
     * {@code RaidCaptureSessionService.offsetFromNearestCenter}), so this animation and the score it
     * produces stay consistent no matter how many passes the player lets go by.
     */
    private static double bounceProgress(long elapsedMs, int travelDurationMs) {
        if (travelDurationMs <= 0) return 1.0;
        long cyclePos = elapsedMs % (travelDurationMs * 2L);
        return cyclePos <= travelDurationMs
                ? cyclePos / (double) travelDurationMs
                : (2.0 * travelDurationMs - cyclePos) / travelDurationMs;
    }

    private String phaseTitle() {
        return switch (phase) {
            case PULSE -> "Stabilizing... (" + (pulseIndex + 1) + "/3)";
            case THROW -> "Throw!";
            case RESULT -> result != null && result.success() ? "Captured!" : "The echo escaped.";
        };
    }

    private String phaseSubtitle() {
        return switch (phase) {
            case PULSE, THROW -> "Press when the indicator crosses the zone.";
            case RESULT -> resultSubtitle();
        };
    }

    private String resultSubtitle() {
        if (result == null) return "";
        if (result.success()) {
            return "delivered".equalsIgnoreCase(result.delivery())
                    ? (result.shiny() ? "Shiny " : "") + result.speciesDisplayName() + " added to your party/PC."
                    : "It will be delivered once you have room.";
        }
        return String.format(java.util.Locale.ROOT,
                "Final chance was %.1f%% -- this raid's Raid Points are on their way instead.",
                result.finalChancePercent());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
