package com.cobbleraids.showdown;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

import java.util.UUID;

/** Applies shared raid damage, credits it to whoever dealt it, and ends the raid when the pool empties. */
public final class RaidDamageInstruction extends RaidPoolInstruction {

    public RaidDamageInstruction(BattleActor actor, BattleMessage publicMessage, BattleMessage privateMessage) {
        super(actor, publicMessage, privateMessage);
    }

    @Override
    protected void applyToPool(RaidSession raid, PokemonBattle battle, float amount) {
        UUID contributor = resolveContributor(battle);
        float applied = raid.damage(contributor, amount);

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Applied raid damage: battle={}, requested={}, applied={}, hp={}/{}, contributor={}",
                    battle.getBattleId(), amount, applied, raid.getCurrentHealth(), raid.getMaxHealth(), contributor);
        }
    }

    /** A won raid is closed out here rather than on a tick, so the victory lands in the same batch. */
    @Override
    protected void afterPool(RaidSession raid, PokemonBattle battle) {
        if (raid.getStatus() == RaidSession.Status.COMPLETED) RaidCompletion.complete(battle);
    }

    /**
     * Who dealt the damage, or null if it came from nobody in particular (weather, a status, the
     * boss hurting itself).
     *
     * <p>Two ways of asking, because Showdown does not always give the same one. The structured
     * lookup is preferred; when it comes back empty the source argument still carries a raw actor
     * token like {@code "p3a"}, whose first two characters name the actor.
     */
    private UUID resolveContributor(PokemonBattle battle) {
        BattlePokemon source = publicMessage.battlePokemon(2, battle);
        if (source != null && source.getActor() != null) return source.getActor().getUuid();

        String sourceArgument = publicMessage.argumentAt(2);
        if (sourceArgument == null || sourceArgument.length() < 2) return null;
        BattleActor sourceActor = battle.getActor(sourceArgument.substring(0, 2));
        return sourceActor == null ? null : sourceActor.getUuid();
    }
}
