package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.renown.RaidRenown;
import com.cobbleraids.renown.RenownBoon;
import com.cobbleraids.reward.NativeRewardScreenGateway;
import com.cobblemon.mod.common.CobblemonSounds;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;

/**
 * The raid reward reveal as a web page: choose, wait for the server's roll, watch the reveal, see
 * what was granted.
 *
 * <p>The server stays the only authority. It remembers which offer it put in front of each player; the
 * page can only say "ready" and name a choice from that offer, and the claim itself runs through
 * {@link NativeRewardScreenGateway#resolveChoice} -- the same code the native screen uses -- so what a
 * player is told was granted is exactly what the claim reported.
 *
 * <p>The reveal's sound is played from here rather than by the page: a page cannot reach the game's
 * sound engine, and the server can play a sound at a player.
 */
public final class RewardWebPage {
    private RewardWebPage() {}

    public static final String CHANNEL = "cobbleraids.reveal";
    public static final String PAGE = "reveal/reveal.html";

    private static final WebIconFeed ICONS = new WebIconFeed("reveal_icons");
    /** The offer each player's open page is showing; the only choices a page may claim. */
    private static final Map<UUID, PendingRewardRevealPayload> OFFERS = new ConcurrentHashMap<>();

    static void register() {
        WebGuiBridge.onPageEvent(CHANNEL, (player, json) ->
                RaidFaultBarrier.guard("reveal-web-event", () -> onEvent(player, json)));
    }

    /** Forgets a disconnected player's offer. */
    public static void forget(UUID player) {
        OFFERS.remove(player);
    }

    /** Opens the page on this offer; the page asks for it once it is listening. */
    public static boolean open(ServerPlayer player, PendingRewardRevealPayload offer) {
        OFFERS.put(player.getUUID(), offer);
        if (WebUi.open(player, PAGE)) return true;
        OFFERS.remove(player.getUUID());
        return false;
    }

    private static void onEvent(ServerPlayer player, String json) {
        JsonObject message = WebJson.object(json);
        if (message == null) return;
        PendingRewardRevealPayload offer = OFFERS.get(player.getUUID());
        if (offer == null) return;
        switch (WebJson.string(message, "action")) {
            case "ready" -> {
                ICONS.reset(player.getUUID());
                WebGuiBridge.emit(player, "reveal_offer", offerJson(offer).toString());
            }
            case "choose" -> {
                String choice = WebJson.bounded(message, "choice", RewardChoicePayload.MAX_CHOICE_ID_LENGTH);
                // Only what was offered: the page is a view, and a claim the offer never listed is refused.
                if (!offer.choiceIds().contains(choice)) return;
                OFFERS.remove(player.getUUID());
                RewardResultPayload result = NativeRewardScreenGateway.resolveChoice(
                        player, new RewardChoicePayload(offer.raidId(), choice));
                deliver(player, offer, result);
            }
            default -> RaidLog.warn("Ignoring unknown reveal page action from {}", player.getGameProfile().getName());
        }
    }

    private static JsonObject offerJson(PendingRewardRevealPayload offer) {
        JsonObject out = new JsonObject();
        RaidRarityTier tier = WebTiers.parse(offer.rarityTier());
        out.addProperty("raidId", offer.raidId().toString());
        out.addProperty("species", offer.speciesDisplayName());
        out.addProperty("tier", offer.rarityTier());
        out.addProperty("tierName", tier == null ? offer.rarityTier() : tier.displayName());
        out.addProperty("tierColor", tier == null ? "#ffffff" : WebTiers.color(tier));
        out.addProperty("elapsedTicks", offer.elapsedCombatTicks());
        out.addProperty("contribution", offer.contributionPercentage());
        out.addProperty("participants", offer.participantCount());
        JsonArray choices = new JsonArray();
        offer.choiceIds().forEach(choices::add);
        out.add("choices", choices);
        String[] title = RaidRenown.splitTitle(offer.renownTitle());
        if (title[0] != null) {
            JsonObject banner = new JsonObject();
            banner.addProperty("name", title[0]);
            if (title[1] != null) banner.addProperty("epithet", title[1]);
            out.add("banner", banner);
            String boon = boonIcon(offer.renownBoon());
            if (boon != null) out.addProperty("boonIcon", boon);
        }
        return out;
    }

    /** The boon's icon name, matching the six {@code boon_*.png} files in the page folder. */
    private static String boonIcon(String encoded) {
        return RenownBoon.decode(encoded).map(boon -> switch (boon.kind()) {
            case HP_POOL -> "hp_pool";
            case STAT_FOCUS -> switch (boon.stat()) {
                case "attack", "defence", "special_attack", "special_defence", "speed" -> boon.stat();
                default -> null;
            };
            case NONE -> null;
        }).orElse(null);
    }

    private static void deliver(ServerPlayer player, PendingRewardRevealPayload offer, RewardResultPayload result) {
        JsonObject out = new JsonObject();
        out.addProperty("raidId", result.raidId().toString());
        out.addProperty("success", result.success());
        out.addProperty("currency", result.currencyGranted());
        out.addProperty("points", result.raidPointsGranted());
        out.addProperty("hasMore", result.hasMoreQueued());
        JsonArray granted = new JsonArray();
        Set<String> wanted = new LinkedHashSet<>();
        wanted.add(WebIcons.FALLBACK_ITEM);
        for (RewardItemPayload item : result.granted()) {
            String namespace = item.item().getNamespace();
            String path = item.item().getPath();
            String key = WebIcons.itemKey(namespace, path);
            if (WebIcons.dataUrl(key).isEmpty()) key = WebIcons.FALLBACK_ITEM;
            JsonObject row = new JsonObject();
            row.addProperty("name", WebIcons.itemName(namespace, path));
            row.addProperty("amount", Math.max(1, item.amount()));
            row.addProperty("icon", key);
            granted.add(row);
            wanted.add(key);
        }
        out.add("granted", granted);
        ICONS.send(player, wanted);
        WebGuiBridge.emit(player, "reveal_result", out.toString());
        if (result.success()) playOpenSound(player, WebTiers.parse(offer.rarityTier()));
    }

    /** The same ball send-out sounds the native screen used: the shiny one for the top two tiers. */
    private static void playOpenSound(ServerPlayer player, RaidRarityTier tier) {
        boolean high = tier == RaidRarityTier.LEGENDARY || tier == RaidRarityTier.MYTHICAL;
        SoundEvent sound = high ? CobblemonSounds.POKE_BALL_SHINY_SEND_OUT : CobblemonSounds.POKE_BALL_SEND_OUT;
        player.playNotifySound(sound, SoundSource.MASTER, 1.0f, 1.0f);
    }
}
