package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.catching.RaidCaptureSessionService;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.cobblemon.mod.common.CobblemonSounds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/**
 * The Raid Capture Protocol as a web page: the choice prompt, three pulses, the throw and the result.
 *
 * <p>The page is a view over {@link RaidCaptureSessionService}. A press arrives as {@code pulse} or
 * {@code throw}, and the service scores it from the server's own receipt time exactly as it does for the
 * native screen; the page never sends, and the server never reads, a timestamp. The page times its marker
 * from when it SENT the previous input, which is the one anchor that cancels the network delay out of a
 * score measured between two server receipts.
 */
public final class CaptureWebPage {
    private CaptureWebPage() {}

    public static final String CHANNEL = "cobbleraids.capture";
    public static final String PAGE = "capture/capture.html";

    private static final WebIconFeed ICONS = new WebIconFeed("capture_icons");

    /** What a player's open page was offered, held until it asks for it. */
    private record Offer(CaptureOfferPayload offer, CaptureDetailsPayload details) {}

    private static final Map<UUID, Offer> OFFERS = new ConcurrentHashMap<>();

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("capture-web-event", () -> onEvent(player, json)));
    }

    /** Forgets a disconnected player's page. */
    public static void forget(UUID player) {
        OFFERS.remove(player);
    }

    /** True while this player's capture is being shown as a page, so replies go there and not to a native screen. */
    public static boolean handles(ServerPlayer player) {
        return OFFERS.containsKey(player.getUUID());
    }

    /** Opens the page on this offer. False, sending nothing, if the client cannot show it. */
    public static boolean open(ServerPlayer player, CaptureOfferPayload offer, CaptureDetailsPayload details) {
        if (!WebUi.canShow(player)) return false;
        OFFERS.put(player.getUUID(), new Offer(offer, details));
        if (WebUi.open(player, PAGE)) return true;
        OFFERS.remove(player.getUUID());
        return false;
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        Offer held = OFFERS.get(player.getUUID());
        if (held == null) return;
        UUID raidId = held.offer().raidId();
        switch (WebJson.string(message, "action")) {
            case "ready" -> {
                ICONS.reset(player.getUUID());
                sendOffer(player, held);
            }
            case "choice" -> {
                boolean attempt = message.has("attempt") && message.get("attempt").isJsonPrimitive()
                        && message.get("attempt").getAsBoolean();
                // Declining settles the session on the spot, so there is nothing left for this page to hear.
                if (!attempt) OFFERS.remove(player.getUUID());
                RaidCaptureSessionService.handleChoice(player, raidId, attempt);
            }
            case "pulse" -> RaidCaptureSessionService.handlePulse(player, raidId, WebJson.integer(message, "index"));
            case "throw" -> RaidCaptureSessionService.handleThrow(player, raidId);
            default -> RaidLog.warn("Ignoring unknown capture page action from {}", player.getGameProfile().getName());
        }
    }

    private static void sendOffer(ServerPlayer player, Offer held) {
        CaptureOfferPayload o = held.offer();
        CaptureDetailsPayload d = held.details();
        JsonObject out = new JsonObject();
        out.addProperty("raidId", o.raidId().toString());
        out.addProperty("tier", o.tier());
        out.addProperty("speciesName", o.speciesDisplayName());
        out.addProperty("shiny", o.shiny());
        out.addProperty("rp", o.raidPointsAtStake());
        out.addProperty("choiceSeconds", o.choiceSecondsRemaining());
        out.add("pulse", timing(o.pulseTravelDurationMs(), o.pulseGoodZoneWidthMs(), o.pulsePerfectZoneWidthMs()));
        out.add("throwT", timing(o.throwTravelDurationMs(), o.throwGoodZoneWidthMs(), o.throwPerfectZoneWidthMs()));
        if (d != null) {
            JsonObject details = new JsonObject();
            details.addProperty("base", d.base());
            details.addProperty("stabCap", d.stabilizationCap());
            details.addProperty("throwCap", d.throwCap());
            details.addProperty("sequenceSeconds", d.sequenceSeconds());
            String key = WebIcons.speciesKey(d.species().getPath(), o.shiny());
            boolean have = WebIcons.dataUrl(key).isPresent();
            details.addProperty("icon", have ? key : "");
            out.add("details", details);
            if (have) ICONS.send(player, Set.of(key));
        }
        WebGuiBridge.emit(player, "capture_offer", out.toString());
    }

    private static JsonObject timing(int travel, int good, int perfect) {
        JsonObject t = new JsonObject();
        t.addProperty("travel", travel);
        t.addProperty("good", good);
        t.addProperty("perfect", perfect);
        return t;
    }

    /** The score for one pulse, as the service just worked it out. */
    public static void deliverPulse(ServerPlayer player, CapturePulseResultPayload payload) {
        JsonObject out = new JsonObject();
        out.addProperty("index", payload.pulseIndex());
        out.addProperty("score", payload.score());
        WebGuiBridge.emit(player, "capture_pulse", out.toString());
        double score = payload.score();
        if (score >= 1) WebSounds.play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.7f);
        else if (score >= 0.5) WebSounds.play(player, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.0f);
        else WebSounds.play(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.8f);
    }

    /** The final result. The page is finished with the session after this. */
    public static void deliverResult(ServerPlayer player, CaptureResultPayload payload) {
        OFFERS.remove(player.getUUID());
        JsonObject out = new JsonObject();
        out.addProperty("success", payload.success());
        out.addProperty("finalChance", payload.finalChancePercent());
        out.addProperty("stab", payload.stabilizationQualityPercent());
        out.addProperty("throwQ", payload.throwQualityPercent());
        out.addProperty("delivery", payload.delivery());
        WebGuiBridge.emit(player, "capture_result", out.toString());
        // The ball shakes for about a second and a half on the page; the verdict sounds as it ends.
        WebSounds.play(player, CobblemonSounds.POKE_BALL_HIT, 1.0f, 1.0f);
        WebSounds.later(player, 30, payload.success() ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.VILLAGER_NO,
                0.8f, 1.0f);
    }
}
