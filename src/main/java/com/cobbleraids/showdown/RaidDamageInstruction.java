package com.cobbleraids.showdown;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies shared raid damage and sends side-correct HP packets to every participant. */
public final class RaidDamageInstruction implements InterpreterInstruction {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleRaids");

    private final BattleActor actor;
    private final BattleMessage publicMessage;
    private final BattleMessage privateMessage;

    public RaidDamageInstruction(BattleActor actor, BattleMessage publicMessage, BattleMessage privateMessage) {
        this.actor = actor;
        this.publicMessage = publicMessage;
        this.privateMessage = privateMessage;
    }

    @Override
    public void invoke(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null) return;

        String raw = privateMessage == null ? null : privateMessage.argumentAt(1);
        if (raw == null) raw = publicMessage.argumentAt(1);
        if (raw == null) return;

        float amount;
        try {
            amount = Float.parseFloat(raw.split("/")[0]);
        } catch (NumberFormatException ignored) {
            return;
        }

        BattlePokemon source = publicMessage.battlePokemon(2, battle);
        UUID contributor = source != null && source.getActor() != null ? source.getActor().getUuid() : null;
        String sourceArgument = publicMessage.argumentAt(2);
        if (contributor == null && sourceArgument != null && sourceArgument.length() >= 2) {
            BattleActor sourceActor = battle.getActor(sourceArgument.substring(0, 2));
            if (sourceActor != null) contributor = sourceActor.getUuid();
        }

        float applied = raid.damage(contributor, amount);
        BattleActor bossActor = battle.getActor(raid.getBossActorId());
        ActiveBattlePokemon bossActive = RaidBattleTargets.bossActive(bossActor);
        BattlePokemon target = bossActive == null
                ? publicMessage.battlePokemon(0, battle)
                : bossActive.getBattlePokemon();
        String pnx = bossActive == null ? RaidBattleTargets.pnx(publicMessage.argumentAt(0)) : bossActive.getPNX();

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            LOGGER.info("Applied raid damage: battle={}, requested={}, applied={}, hp={}/{}, contributor={}",
                    battle.getBattleId(), amount, applied, raid.getCurrentHealth(), raid.getMaxHealth(), contributor);
        }

        if (target != null && target.getEffectedPokemon() != null && bossActor != null && pnx != null) {
            float ratio = Math.max(0.0f, Math.min(1.0f, raid.getCurrentHealth() / raid.getMaxHealth()));
            int displayHp = Math.max(0, Math.round(raid.getCurrentHealth()));
            int physicalMax = target.getEffectedPokemon().getMaxHealth();
            target.getEffectedPokemon().setCurrentHealth(Math.max(0, Math.round(physicalMax * ratio)));
            battle.sendSidedUpdate(
                    bossActor,
                    new BattleHealthChangePacket(pnx, displayHp, raid.getMaxHealth()),
                    new BattleHealthChangePacket(pnx, ratio, null),
                    false
            );
        }

        if (raid.getStatus() == RaidSession.Status.COMPLETED) {
            RaidCompletion.complete(battle);
        }
    }
}
