package com.cobbleraids.interaction;

import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Intercepts only marked raid bosses, before Cobblemon's normal entity interaction can begin a solo battle. */
public final class RaidBossInteractionListener {
    private RaidBossInteractionListener() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (!(entity instanceof PokemonEntity pokemonEntity) || !RaidBossEntityMarker.isRaidBoss(pokemonEntity)) return InteractionResult.PASS;
            if (serverPlayer.isSpectator()) return InteractionResult.FAIL;

            RaidLobbyManager.interact(serverPlayer, pokemonEntity);
            // Any non-PASS result on the logical server cancels downstream interaction processing.
            return InteractionResult.SUCCESS;
        });
    }
}
