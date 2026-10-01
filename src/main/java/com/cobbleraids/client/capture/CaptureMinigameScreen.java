package com.cobbleraids.client.capture;

import com.cobbleraids.client.shop.CapturePokemonPreview;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseInputPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.cobbleraids.network.CaptureThrowInputPayload;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Raid Capture Protocol's timing minigame: three stabilization pulses, then a throw. The server
 * scores every input off its own clock and rolls exactly once -- this screen only ever animates
 * toward an already-known result once {@link CaptureResultPayload} arrives; it never decides an
 * outcome locally.
 */
public final class CaptureMinigameScreen extends Screen {
    private enum Phase { PULSE, THROW, RESULT }

    /** The result reveal holds on a "checking" state this long before showing success/failure. */
    private static final long REVEAL_DELAY_NANOS = 1_500_000_000L;

    private final CaptureOfferPayload offer;
    private final CaptureDetailsPayload details;
    private CaptureUiLayout layout;

    private Phase phase = Phase.PULSE;
    private int pulseIndex;
    private final double[] pulseScores = new double[3];
    private long stepStartedAtNanos = System.nanoTime();
    private long resultRevealedAtNanos;
    private final long sequenceStartedAtNanos = System.nanoTime();
    private boolean waitingForServer;
    private boolean spaceHeld;
    private CaptureResultPayload result;
    private CaptureUi.Action actionButton;

    private CaptureMinigameScreen(CaptureOfferPayload offer, CaptureDetailsPayload details) {
        super(Component.literal("Raid Capture Protocol"));
        this.offer = offer;
        this.details = details;
    }

    public static void openFor(CaptureOfferPayload offer) {
        openFor(offer, CaptureDetailsCache.take(offer.raidId()));
    }

    static void openFor(CaptureOfferPayload offer, CaptureDetailsPayload details) {
        Minecraft.getInstance().setScreen(new CaptureMinigameScreen(offer, details));
    }

    /** No-op if the current screen isn't the matching minigame, or the pulse isn't the one expected. */
    public static void applyPulseResult(CapturePulseResultPayload payload) {
        if (!(Minecraft.getInstance().screen instanceof CaptureMinigameScreen screen)) return;
        if (!screen.offer.raidId().equals(payload.raidId())) return;
        if (screen.phase != Phase.PULSE || screen.pulseIndex != payload.pulseIndex()) return;

        screen.pulseScores[screen.pulseIndex] = Math.clamp(payload.score(), 0, 1);
        screen.waitingForServer = false;
        if (screen.pulseIndex == 2) {
            screen.phase = Phase.THROW;
        } else {
            screen.pulseIndex++;
        }
        // The next server step starts before this acknowledgment travels to us; our input must
        // travel back again. Use vanilla's bounded round-trip latency estimate for display only --
        // the server still scores the real input against its own clock, not this estimate.
        screen.stepStartedAtNanos = System.nanoTime() - screen.estimatedLatencyNanos();
        screen.updateActionButton();
    }

    /** No-op if the current screen isn't the matching minigame -- e.g. the player already closed it. */
    public static void applyResult(CaptureResultPayload payload) {
        if (!(Minecraft.getInstance().screen instanceof CaptureMinigameScreen screen)) return;
        if (!screen.offer.raidId().equals(payload.raidId()) || screen.result != null) return;

        screen.result = payload;
        screen.phase = Phase.RESULT;
        screen.waitingForServer = false;
        screen.resultRevealedAtNanos = System.nanoTime();
        screen.init();
    }

    private long estimatedLatencyNanos() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) return 0;
        var info = client.getConnection().getPlayerInfo(client.player.getUUID());
        int latencyMs = info == null ? 0 : Math.clamp(info.getLatency(), 0, 500);
        return latencyMs * 1_000_000L;
    }

    @Override
    protected void init() {
        clearWidgets();
        layout = CaptureUiLayout.fit(width, height);
        int buttonWidth = Math.min(180, layout.width() - 32);
        actionButton = addRenderableWidget(new CaptureUi.Action(font, layout.x() + (layout.width() - buttonWidth) / 2,
                layout.buttonY(), buttonWidth, "", this::onAction));
        updateActionButton();
    }

    private boolean revealing() {
        return result != null && (System.nanoTime() - resultRevealedAtNanos) < REVEAL_DELAY_NANOS;
    }

    private void updateActionButton() {
        if (actionButton == null) return;
        String label = switch (phase) {
            case RESULT -> revealing() ? "Checking..." : "Close";
            default -> waitingForServer ? "Scoring..." : phase == Phase.THROW ? "Throw Ball [SPACE]" : "Stabilize [SPACE]";
        };
        actionButton.setMessage(Component.literal(label));
        actionButton.active = !waitingForServer && !revealing();
    }

    private void onAction() {
        if (waitingForServer || revealing()) return;
        if (phase == Phase.RESULT) {
            onClose();
            return;
        }
        waitingForServer = true;
        updateActionButton();
        if (phase == Phase.PULSE) {
            ClientPlayNetworking.send(new CapturePulseInputPayload(offer.raidId(), pulseIndex));
        } else {
            ClientPlayNetworking.send(new CaptureThrowInputPayload(offer.raidId()));
        }
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (!spaceHeld) {
                spaceHeld = true;
                onAction();
            }
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            spaceHeld = false;
            return true;
        }
        return super.keyReleased(key, scancode, modifiers);
    }

    @Override
    public void tick() {
        updateActionButton();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // See CaptureOfferScreen's identical override -- reference_screen_background_blur.
    }

    @Override
    public void removed() {
        CapturePokemonPreview.clear();
        CaptureArt.release();
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);

        String heading = switch (phase) {
            case RESULT -> revealing() ? "CAPTURE CHECK" : result.success() ? "CAPTURED!" : "THE ECHO ESCAPED";
            case THROW -> "THROW THE BALL";
            case PULSE -> "STABILIZE ECHO  " + (pulseIndex + 1) + " / 3";
        };
        if (phase != Phase.RESULT && details != null) {
            long elapsedSeconds = (System.nanoTime() - sequenceStartedAtNanos) / 1_000_000_000L;
            int remaining = Math.max(0, details.sequenceSeconds() - (int) elapsedSeconds);
            heading += "  |  " + remaining + "s";
        }
        CaptureUi.frame(g, font, layout, heading);

        boolean showBall = (phase == Phase.THROW && waitingForServer) || revealing();
        long resultAnimationMs = result == null ? 0 : (System.nanoTime() - resultRevealedAtNanos) / 1_000_000L;
        CaptureUi.chamber(g, font, layout, offer, details, partialTick, showBall, resultAnimationMs,
                phase == Phase.RESULT && !revealing() && !result.success());

        int pulsesCompleted = phase == Phase.PULSE ? pulseIndex : 3;
        String lampLabel = phase == Phase.RESULT ? "COMPLETE" : phase == Phase.THROW ? "THROW READY" : "STABILIZING";
        CaptureArt.lamps(g, font, layout, lampLabel, pulsesCompleted);
        CaptureArt.meter(g, layout, averagePulseScore());

        drawInfoColumn(g);
        if (phase == Phase.RESULT) {
            drawResult(g);
        } else {
            drawTiming(g);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private double averagePulseScore() {
        return (pulseScores[0] + pulseScores[1] + pulseScores[2]) / 3.0;
    }

    private void drawInfoColumn(GuiGraphics g) {
        int x = layout.infoX() + 9, y = layout.contentY() + 10, w = layout.infoWidth() - 18;
        CaptureUi.text(g, font, (offer.shiny() ? "Shiny " : "") + offer.speciesDisplayName(), x, y, w, CaptureUi.WHITE);
        CaptureUi.text(g, font, offer.tier().toUpperCase(Locale.ROOT), x, y + 14, w, CaptureUi.CYAN);
        CaptureUi.text(g, font, offer.raidPointsAtStake() + " RP reserved", x, y + 29, w, CaptureUi.WHITE);

        int crystalX = x + font.width(offer.raidPointsAtStake() + " RP reserved") + 5;
        if (crystalX + 9 < x + w) CaptureArt.crystal(g, crystalX, y + 26);

        if (layout.contentHeight() >= 78) {
            String odds;
            if (result != null && !revealing()) {
                odds = String.format(Locale.ROOT, "Final chance: %.1f%%", result.finalChancePercent());
            } else if (details == null) {
                odds = "Server scored";
            } else {
                double stabilizationBonus = averagePulseScore() * details.stabilizationCap();
                String suffix = w < 110 ? " + bonus" : " + throw bonus";
                odds = CaptureUi.pct(Math.min(1, details.base() + stabilizationBonus)) + suffix;
            }
            CaptureUi.text(g, font, odds, x, y + 48, w, CaptureUi.CYAN);

            if (layout.contentHeight() >= 125) {
                int pulsesCompleted = phase == Phase.PULSE ? pulseIndex : 3;
                for (int i = 0; i < 3; i++) {
                    String state = i >= pulsesCompleted ? "--"
                            : pulseScores[i] >= 1 ? "PERFECT" : pulseScores[i] >= 0.5 ? "GOOD" : "MISSED";
                    int color = i >= pulsesCompleted ? CaptureUi.MUTED : pulseScores[i] > 0 ? CaptureUi.CYAN : 0xFFFFC46B;
                    CaptureUi.text(g, font, (i + 1) + "  " + state, x, y + 67 + i * 13, w, color);
                }
            }
        }
        if (layout.contentHeight() >= 180) {
            int bottom = CaptureUi.wrap(g, font, "Match the white marker to the center zone. Misses keep your base chance.",
                    x, y + 115, w, CaptureUi.WHITE);
            CaptureArt.scanner(g, font, x, bottom + 5, w, layout.contentY() + layout.contentHeight() - bottom - 14,
                    phase == Phase.PULSE ? pulseIndex : 3);
        }
    }

    private void drawTiming(GuiGraphics g) {
        String prompt = waitingForServer ? "Waiting for server score..."
                : phase == Phase.THROW ? "Time your throw at the center!" : "Press SPACE or click Stabilize at the center";
        CaptureUi.centered(g, font, prompt, layout.x() + 12, layout.y() + layout.height() - 99, layout.width() - 24, CaptureUi.WHITE);

        int trackX = layout.trackX(), trackY = layout.trackY(), trackWidth = layout.trackWidth(), trackHeight = 18;
        CaptureUi.panel(g, trackX - 3, trackY - 3, trackWidth + 6, trackHeight + 6, CaptureUi.NAVY, 0xFF129EC6);

        int travel = phase == Phase.THROW ? offer.throwTravelDurationMs() : offer.pulseTravelDurationMs();
        int good = layout.zoneWidth(phase == Phase.THROW ? offer.throwGoodZoneWidthMs() : offer.pulseGoodZoneWidthMs(), travel);
        int perfect = layout.zoneWidth(phase == Phase.THROW ? offer.throwPerfectZoneWidthMs() : offer.pulsePerfectZoneWidthMs(), travel);

        for (int i = 1; i < 16; i++) {
            int tick = trackX + i * trackWidth / 16;
            g.fill(tick, trackY + 5, tick + 1, trackY + trackHeight - 5, CaptureUi.CYAN);
        }
        CaptureUi.text(g, font, "<", trackX + 3, trackY + 5, 10, CaptureUi.CYAN);
        CaptureUi.text(g, font, ">", trackX + trackWidth - 8, trackY + 5, 10, CaptureUi.CYAN);

        int center = trackX + trackWidth / 2;
        g.fill(center - good / 2, trackY, center + (good + 1) / 2, trackY + trackHeight, 0xFFFFB32F);
        g.fill(center - perfect / 2, trackY, center + (perfect + 1) / 2, trackY + trackHeight, 0xFF00CDEB);
        g.fill(center - 1, trackY + 4, center + 1, trackY + trackHeight - 4, CaptureUi.WHITE);

        long elapsedMs = (System.nanoTime() - stepStartedAtNanos) / 1_000_000L;
        int indicatorX = layout.indicator(CaptureUiLayout.bounce(elapsedMs, travel));
        g.fill(indicatorX - 1, trackY - 3, indicatorX + 2, trackY + trackHeight + 3, CaptureUi.WHITE);

        CaptureUi.centered(g, font, "GOLD: GOOD   |   CYAN CENTER: PERFECT",
                layout.x() + 12, trackY + 27, layout.width() - 24, CaptureUi.MUTED);
    }

    private void drawResult(GuiGraphics g) {
        int messageY = layout.y() + layout.height() - 99;
        if (revealing()) {
            CaptureUi.centered(g, font, "Checking the capture...", layout.x() + 12, layout.trackY(), layout.width() - 24, CaptureUi.CYAN);
            return;
        }
        String line = result.success()
                ? ("DELIVERED".equalsIgnoreCase(result.delivery())
                        ? "Added to your party / PC." : "Captured! Delivery waits for party / PC space.")
                : "You keep all " + offer.raidPointsAtStake() + " RP from this raid.";
        CaptureUi.wrap(g, font, line, layout.x() + 16, messageY, layout.width() - 32, CaptureUi.WHITE);

        String detail = result.success()
                ? "Capture replaces this raid's " + offer.raidPointsAtStake() + " RP. Other loot is yours."
                : String.format(Locale.ROOT, "Final chance %.1f%%. Other raid loot is yours.", result.finalChancePercent());
        CaptureUi.wrap(g, font, detail, layout.x() + 16, layout.y() + layout.height() - 68, layout.width() - 32, CaptureUi.CYAN);
    }
}
