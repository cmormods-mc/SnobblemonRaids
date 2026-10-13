package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
import com.cobbleraids.web.RewardWebPage;
import com.cobbleraids.web.WebUi;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Bridges the server-authoritative reward queue to the client's native reveal screen, when present. */
public final class NativeRewardScreenGateway {
    private NativeRewardScreenGateway() {}

    /** Offers the native screen to a player. False means the client can't/won't render it -- caller should fall back. */
    public static boolean tryOpen(ServerPlayer player, PendingRaidReward pending) {
        // A client with WebGUI gets the web page; anyone else the native screen if they have it.
        boolean web = WebUi.canShow(player);
        if (!web && !ServerPlayNetworking.canSend(player, PendingRewardRevealPayload.TYPE)) return false;
        RaidDefinition definition = RaidDefinitionRegistry.get(pending.definitionId());
        if (definition == null) return false;
        PendingRewardRevealPayload offer = new PendingRewardRevealPayload(
                pending.raidId(),
                pending.definitionId(),
                pending.rarityTier().serializedName(),
                speciesDisplayName(definition),
                pending.renownTitle(),
                pending.renownBoon(),
                List.copyOf(pending.rewards().choices().keySet()),
                pending.contributionPercentage(),
                pending.contributionBonusRolls(),
                pending.elapsedCombatTicks(),
                pending.participantCount());
        if (web) return RewardWebPage.open(player, offer);
        ServerPlayNetworking.send(player, offer);
        return true;
    }

    /** Handles a RewardChoicePayload received from the native client and reports back the actual result. */
    public static void handleChoice(ServerPlayer player, RewardChoicePayload payload) {
        ServerPlayNetworking.send(player, resolveChoice(player, payload));
    }

    /**
     * Claims a choice and describes what happened. The one place a claim is turned into a reply, so
     * the native screen and the web page can only ever be told the same thing.
     */
    public static RewardResultPayload resolveChoice(ServerPlayer player, RewardChoicePayload payload) {
        RewardGrantResult result = RaidRewardService.claimNative(player, payload.choiceId());
        boolean success = result != null;
        List<RewardItemPayload> granted = success
                ? result.allGranted().stream().map(item -> new RewardItemPayload(item.item(), item.amount())).toList()
                : List.of();
        boolean hasMoreQueued = RaidRewardService.hasPending(player.getUUID());
        // longValue() is safe: Currency caps a tier amount at a billion, and the policy only ever
        // scales that down. A failed claim reports zero rather than what the policy would have paid.
        long currency = success ? result.currencyGranted().longValue() : 0L;
        int points = success ? result.raidPointsGranted() : 0;
        return new RewardResultPayload(payload.raidId(), success, granted, currency, points, hasMoreQueued);
    }

    private static String speciesDisplayName(RaidDefinition definition) {
        Species species = PokemonSpecies.getByIdentifier(definition.species());
        return species != null ? species.getTranslatedName().getString() : definition.species().getPath();
    }
}
