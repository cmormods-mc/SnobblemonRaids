package com.cobbleraids.mixin.battle;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleActorErrors;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A raid boss may only ever be in a battle the raid system started.
 *
 * <p>RaidBossInteractionListener blocks the right-click route into a solo wild battle, but that is
 * one input path among several -- throwing a Pokemon at the boss reaches the same place without
 * going through UseEntityCallback at all, which let a player open a private wild battle against the
 * boss and skip recruitment entirely. Guarding each input path in turn is a losing game, so this
 * sits on the single static choke point every battle in Cobblemon passes through instead: whatever
 * new way a future version finds to start one, it still has to call startBattle.
 *
 * <p>The raid's own battles carry gameType "raid" and are let through untouched; anything else
 * holding a marked boss is refused.
 */
@Mixin(BattleRegistry.class)
public abstract class RaidBossBattleStartGuardMixin {
    @Inject(
            method = "startBattle(Lcom/cobblemon/mod/common/battles/BattleFormat;Lcom/cobblemon/mod/common/battles/BattleSide;Lcom/cobblemon/mod/common/battles/BattleSide;Z)Lcom/cobblemon/mod/common/battles/BattleStartResult;",
            at = @At("HEAD"),
            cancellable = true)
    private static void cobbleRaids$refuseUnmanagedBossBattle(
            BattleFormat format,
            BattleSide side1,
            BattleSide side2,
            boolean party,
            CallbackInfoReturnable<BattleStartResult> cir) {
        if ("raid".equals(format.getBattleType().getName())) return;

        PokemonEntity boss = findRaidBoss(side1, side2);
        if (boss == null) return;

        tellParticipants(boss, side1, side2);
        // Empty errors on purpose: Cobblemon's own messages describe ordinary battle failures
        // ("that Pokemon is busy") and would misdescribe this. The players are told directly above.
        cir.setReturnValue(new ErroredBattleStart(new HashSet<>(), new BattleActorErrors()));
    }

    private static PokemonEntity findRaidBoss(BattleSide... sides) {
        for (BattleSide side : sides) {
            if (side == null) continue;
            for (BattleActor actor : side.getActors()) {
                for (BattlePokemon pokemon : actor.getPokemonList()) {
                    PokemonEntity entity = pokemon.getEntity();
                    if (RaidBossEntityMarker.isRaidBoss(entity)) return entity;
                }
            }
        }
        return null;
    }

    /** Without this the throw simply does nothing, which reads as the boss being broken. */
    private static void tellParticipants(PokemonEntity boss, BattleSide... sides) {
        if (!(boss.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        Set<UUID> told = new LinkedHashSet<>();
        for (BattleSide side : sides) {
            if (side == null) continue;
            for (BattleActor actor : side.getActors()) {
                for (UUID playerId : actor.getPlayerUUIDs()) {
                    if (!told.add(playerId)) continue;
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                        "That is a raid boss. Right-click it to join the raid instead.")
                                .withStyle(ChatFormatting.RED));
                    }
                }
            }
        }
    }
}
