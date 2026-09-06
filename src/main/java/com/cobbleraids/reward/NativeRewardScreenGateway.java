package com.cobbleraids.reward;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.network.PendingRewardRevealPayload;
import com.cobbleraids.network.RewardChoicePayload;
import com.cobbleraids.network.RewardItemPayload;
import com.cobbleraids.network.RewardResultPayload;
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
        if (!ServerPlayNetworking.canSend(player, PendingRewardRevealPayload.TYPE)) return false;
        RaidDefinition definition = RaidDefinitionRegistry.get(pending.definitionId());
        if (definition == null) return false;
        ServerPlayNetworking.send(player, new PendingRewardRevealPayload(
                pending.raidId(),
                pending.definitionId(),
                pending.rarityTier().serializedName(),
                speciesDisplayName(definition),
                List.copyOf(pending.rewards().choices().keySet()),
                pending.contributionPercentage(),
                pending.contributionBonusRolls()));
        return true;
    }

    /** Handles a RewardChoicePayload received from the client and reports back the actual result. */
    public static void handleChoice(ServerPlayer player, RewardChoicePayload payload) {
        RewardGrantResult result = RaidRewardService.claimNative(player, payload.choiceId());
        boolean success = result != null;
        List<RewardItemPayload> granted = success
                ? result.allGranted().stream().map(item -> new RewardItemPayload(item.item(), item.amount())).toList()
                : List.of();
        boolean hasMoreQueued = RaidRewardService.hasPending(player.getUUID());
        ServerPlayNetworking.send(player, new RewardResultPayload(payload.raidId(), success, granted, hasMoreQueued));
    }

    private static String speciesDisplayName(RaidDefinition definition) {
        Species species = PokemonSpecies.getByName(definition.species().getPath());
        return species != null ? species.getTranslatedName().getString() : definition.species().getPath();
    }
}
