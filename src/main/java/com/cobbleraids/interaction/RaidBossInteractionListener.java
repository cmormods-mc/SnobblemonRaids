package com.cobbleraids.interaction;

import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Intercepts only marked raid bosses, before Cobblemon's own UseEntityCallback handler can begin a
 * solo battle. Registered on an explicit phase ordered before Event.DEFAULT_PHASE: Cobblemon's own
 * listener registers on the default phase, and since cobbleraids depends on cobblemon, Fabric Loader
 * always initializes Cobblemon first -- without an explicit earlier phase, Cobblemon's handler runs
 * first every time and can already return a non-PASS result (starting a wild battle) before this
 * listener ever executes, since Event<UseEntityCallback> short-circuits on the first non-PASS result.
 */
public final class RaidBossInteractionListener {
    private static final ResourceLocation EARLY_PHASE =
            ResourceLocation.fromNamespaceAndPath("cobbleraids", "raid_boss_interaction");

    private RaidBossInteractionListener() {}

    public static void register() {
        UseEntityCallback.EVENT.addPhaseOrdering(EARLY_PHASE, Event.DEFAULT_PHASE);
        UseEntityCallback.EVENT.register(EARLY_PHASE, (player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (!(entity instanceof PokemonEntity pokemonEntity)) return InteractionResult.PASS;

            // Identification is guarded separately from the join, because the safe fallback differs
            // and depends on what we still know. If we cannot even tell whether this is a raid boss,
            // PASS is right: the overwhelming majority of right-clicks are on ordinary entities, and
            // swallowing those would break unrelated gameplay for every player.
            boolean isRaidBoss;
            try {
                isRaidBoss = RaidBossEntityMarker.isRaidBoss(pokemonEntity);
            } catch (Exception ex) {
                RaidFaultBarrier.report("interact:identify", ex);
                return InteractionResult.PASS;
            }
            if (!isRaidBoss) return InteractionResult.PASS;
            if (serverPlayer.isSpectator()) return InteractionResult.FAIL;

            // One right-click on an entity makes a vanilla client send two packets, INTERACT_AT
            // and then INTERACT, and Fabric fires this event from a separate injection point for
            // each (see its ServerPlayNetworkHandlerMixin). Both arrive in the same tick with
            // MAIN_HAND, so the hand check above does not separate them; the only thing that does
            // is the hit result, which only the INTERACT_AT variant carries. Acting on both ran
            // the join twice and sent the player two messages ("Joined raid" immediately followed
            // by "You are already in this raid", or that line twice when re-clicking).
            //
            // INTERACT_AT is the one to act on: a vanilla client always sends it for an entity,
            // whereas INTERACT follows only when the client-side interactAt did not consume.
            // Past this point we know it is a raid boss, so the fallback flips: whatever happens,
            // do not return PASS. Cobblemon's own handler would take the packet and start an
            // ordinary solo wild battle against a raid boss, which is the exact situation this
            // listener exists to prevent -- a failed join is recoverable, that is not.
            if (hitResult != null) {
                try {
                    RaidLobbyManager.interact(serverPlayer, pokemonEntity);
                } catch (Exception ex) {
                    RaidFaultBarrier.report("interact:join", ex);
                    serverPlayer.sendSystemMessage(Component.literal(
                                    "Could not join this raid. Tell an administrator.")
                            .withStyle(ChatFormatting.RED));
                }
            }

            // Both variants still return a non-PASS result, which cancels downstream interaction
            // processing. The ignored one must NOT return PASS: that would hand its packet to
            // Cobblemon's own handler, which starts an ordinary solo wild battle against the boss.
            return InteractionResult.SUCCESS;
        });
    }
}
