package com.cobbleraids.lifecycle;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.battles.runner.ShowdownService;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/** Failure-only retirement for a raid's explicit timeout/withdrawal/abort termination. */
final class RaidBattleTermination {
    private static Field registryField;

    private RaidBattleTermination() {}

    static void end(PokemonBattle battle) {
        BattleTerminationRecovery.finish(battle::getEnded, battle::end,
                () -> BattleRegistry.getBattle(battle.getBattleId()) == battle,
                () -> retireIncomplete(battle));
    }

    private static void retireIncomplete(PokemonBattle battle) {
        // closeBattle invokes arbitrary onEnd handlers before removing its map entry. Calling it again
        // could repeat handlers which already ran, including progression. Only the failed entry is retired.
        if (!removeExactBattle(battle)) return;
        battle.setEnded(true);
        UUID battleId = battle.getBattleId();
        RaidFaultBarrier.guard("termination:actors", () -> {
            for (var actor : battle.getActors()) {
                for (BattlePokemon pokemon : actor.getPokemonList()) {
                    RaidFaultBarrier.guard("termination:entity", () -> releaseEntity(pokemon, battleId));
                }
            }
        });
        RaidFaultBarrier.guard("termination:players", () -> {
            for (var player : battle.getPlayers()) {
                RaidFaultBarrier.guard("termination:player", () -> {
                    // A third-party callback may already have moved this player into another battle.
                    if (BattleRegistry.getBattleByParticipatingPlayer(player) == null) {
                        new BattleEndPacket().sendToPlayer(player);
                    }
                });
            }
        });
        RaidFaultBarrier.guard("termination:showdown", () -> ShowdownService.Companion.getService().endBattle(battle));
        RaidLog.warn("Retired incomplete raid battle {} without replaying its end callbacks", battleId);
    }

    private static void releaseEntity(BattlePokemon pokemon, UUID battleId) {
        var entity = pokemon.getEntity();
        if (entity == null || entity.isRemoved()) return;
        UUID current = entity.getBattleId();
        if (current != null && !battleId.equals(current)) return;
        if (battleId.equals(current)) entity.setBattleId(null);
        // A party's real Pokemon must never be discarded by fault recovery.
        if (pokemon.getEffectedPokemon() != pokemon.getOriginalPokemon() && entity.isBattleClone()) entity.discard();
    }

    /** The public close API is unsafe to retry after partial callbacks, so this narrow fallback uses
     * Cobblemon 1.8.2's verified registry field and compare-removes only the exact failed instance. */
    private static boolean removeExactBattle(PokemonBattle battle) {
        try {
            if (registryField == null) {
                Field field = BattleRegistry.class.getDeclaredField("battleMap");
                field.setAccessible(true);
                registryField = field;
            }
            Object value = registryField.get(null);
            if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Unexpected Cobblemon battle registry type");
            return map.remove(battle.getBattleId(), battle);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not retire incomplete Cobblemon battle " + battle.getBattleId(), ex);
        }
    }
}
