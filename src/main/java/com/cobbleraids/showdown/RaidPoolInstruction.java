package com.cobbleraids.showdown;

import com.cobbleraids.fault.RaidThreadGuard;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;

/**
 * Shared machinery for the two Showdown instructions that move the raid's shared health pool.
 *
 * <p>{@code -raiddamage} and {@code -raidheal} are the same instruction in opposite directions, and
 * before this they were the same code twice: parse an amount out of the message, move the pool by
 * it, then push the boss's new health to both sides of the battle. Only the middle step differs.
 *
 * <p>The last step is the one worth understanding. A raid boss's real Pokemon has an ordinary
 * Cobblemon max HP that has nothing to do with the raid's pool, so the entity's health is set to the
 * same *fraction* the pool is at, while the number shown to players is the pool's own value. That is
 * why two packets go out: the boss's side gets absolute pool HP, everyone else gets a ratio.
 */
abstract class RaidPoolInstruction implements InterpreterInstruction {

    protected final BattleActor actor;
    protected final BattleMessage publicMessage;
    protected final BattleMessage privateMessage;

    protected RaidPoolInstruction(BattleActor actor, BattleMessage publicMessage, BattleMessage privateMessage) {
        this.actor = actor;
        this.publicMessage = publicMessage;
        this.privateMessage = privateMessage;
    }

    @Override
    public final void invoke(PokemonBattle battle) {
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null) return;

        Float amount = parseAmount();
        if (amount == null) return;

        applyToPool(raid, battle, amount);
        // syncBossHealth writes a Pokemon's health and sends packets, neither of which is safe off
        // the server thread. Cobblemon reaches this code without marshalling, so whether we are on
        // it is a property of the installed Cobblemon version rather than of anything we control.
        // Ask, report, and carry on -- behaviour is unchanged either way.
        RaidThreadGuard.expectServerThread("showdown-instruction");
        syncBossHealth(raid, battle);
        afterPool(raid, battle);
    }

    /** Moves the shared pool. Called once the amount is known and the raid is confirmed. */
    protected abstract void applyToPool(RaidSession raid, PokemonBattle battle, float amount);

    /** Runs after the health packets go out. Damage uses it to close out a won raid. */
    protected void afterPool(RaidSession raid, PokemonBattle battle) {}

    /**
     * The amount, or null when there is nothing usable to read.
     *
     * <p>The private message is preferred because Showdown only puts exact numbers there; the public
     * one carries the same field rounded for spectators. Values arrive as {@code "120/255"}, so
     * everything from the slash on is discarded.
     */
    private Float parseAmount() {
        String raw = privateMessage == null ? null : privateMessage.argumentAt(1);
        if (raw == null) raw = publicMessage.argumentAt(1);
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.split("/")[0]);
        } catch (NumberFormatException ignored) {
            // A malformed argument is Showdown's problem, not a reason to abort the whole batch.
            return null;
        }
    }

    /**
     * Pushes the pool's new state to both sides: the boss's actor sees absolute pool HP, everyone
     * else a ratio, and the real entity is moved to the same fraction so its model matches the bar.
     */
    private void syncBossHealth(RaidSession raid, PokemonBattle battle) {
        BattleActor bossActor = battle.getActor(raid.getBossActorId());
        ActiveBattlePokemon bossActive = RaidBattleTargets.bossActive(bossActor);
        BattlePokemon target = bossActive == null
                ? publicMessage.battlePokemon(0, battle)
                : bossActive.getBattlePokemon();
        String pnx = bossActive == null ? RaidBattleTargets.pnx(publicMessage.argumentAt(0)) : bossActive.getPNX();
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
}
