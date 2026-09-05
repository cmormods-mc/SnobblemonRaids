package com.cobbleraids.showdown;

import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;

/** Applies shared raid healing and keeps boss-side and player-side HP displays synchronized. */
public final class RaidHealInstruction implements InterpreterInstruction {
    private final BattleActor actor;
    private final BattleMessage publicMessage;
    private final BattleMessage privateMessage;

    public RaidHealInstruction(BattleActor actor, BattleMessage publicMessage, BattleMessage privateMessage) {
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

        raid.heal(amount);
        BattleActor bossActor = battle.getActor(raid.getBossActorId());
        ActiveBattlePokemon bossActive = bossActor == null ? null : bossActor.getActivePokemon().stream()
                .filter(pokemon -> pokemon != null && !pokemon.isGone())
                .findFirst()
                .orElse(null);
        BattlePokemon target = bossActive == null
                ? publicMessage.battlePokemon(0, battle)
                : bossActive.getBattlePokemon();
        String pnx = bossActive == null ? pnx(publicMessage.argumentAt(0)) : bossActive.getPNX();
        if (target == null || target.getEffectedPokemon() == null || bossActor == null || pnx == null) return;

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

    private static String pnx(String argument) {
        if (argument == null) return null;
        int colon = argument.indexOf(':');
        return colon <= 0 ? null : argument.substring(0, colon);
    }
}
