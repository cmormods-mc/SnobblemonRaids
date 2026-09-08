package com.cobbleraids.lifecycle;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveSet;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries a raid's battle damage back onto the players' real Pokemon -- the cost of a raid.
 *
 * <p>The exact mirror of {@link RaidProgressionTransfer}, and it exists for the same reason. A raid
 * is fought on clones, so Damage, Heal, Switch and Faint all write only to {@code effectedPokemon}
 * and the party comes out untouched. That made raids free: no HP lost, no PP spent, nothing to heal
 * afterwards. This copies the chosen parts of that state across so a raid asks something of the
 * player, while the clone still owns everything else.
 *
 * <p>Health brings faints with it at no extra cost: {@code Pokemon.isFainted()} is exactly
 * {@code currentHealth <= 0}, and Cobblemon's own faint timer then revives on its usual schedule, so
 * the penalty expires by itself. Status is available but off by default -- see
 * {@link CobbleRaidsConfig.BattleCarryover}.
 *
 * <p>Applied on every terminal outcome, win or lose. Cost-on-loss-only inverts the incentive: it
 * makes a narrowly-won raid strictly cheaper than a careful one, and leaves the bosses you can
 * actually beat costing nothing at all.
 *
 * <p>Unlike rewards, this deliberately does <em>not</em> skip players who withdrew or disconnected.
 * Rewards are earned and are forfeited by leaving; damage was already taken, and letting a
 * disconnect erase it would make quitting mid-raid the cheapest way to fight one.
 */
public final class RaidBattleStateCarryover {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleRaids");

    private RaidBattleStateCarryover() {}

    /** Call once, on any terminal raid transition, while the battle's actors are still readable. */
    public static void apply(RaidSession raid) {
        if (raid == null) return;
        CobbleRaidsConfig.BattleCarryover carryover = CobbleRaidsConfigManager.get().battleCarryover();
        // The common configuration for a server that wants raids to stay free; skip the whole walk.
        if (carryover.isNoOp()) return;

        PokemonBattle battle = raid.getBattle();
        for (BattleActor actor : battle.getActors()) {
            if (!(actor instanceof PlayerBattleActor)) continue;
            for (BattlePokemon battlePokemon : actor.getPokemonList()) {
                try {
                    applyTo(battlePokemon, carryover);
                } catch (RuntimeException ex) {
                    // One Pokemon must not stop the rest of the party -- or the raid -- finalizing.
                    LOGGER.error("[CobbleRaids] Failed to carry raid battle state for {}",
                            battlePokemon.getOriginalPokemon().getSpecies().getName(), ex);
                }
            }
        }
    }

    private static void applyTo(BattlePokemon battlePokemon, CobbleRaidsConfig.BattleCarryover carryover) {
        Pokemon original = battlePokemon.getOriginalPokemon();
        Pokemon clone = battlePokemon.getEffectedPokemon();
        // A team that was never cloned already holds its own battle damage; copying is a no-op at
        // best and, for health, would re-clamp a value Cobblemon has already committed.
        if (original == clone) return;

        // Health first: setCurrentHealth clears status as part of its own bookkeeping, so writing
        // status before it would be undone.
        if (carryover.health()) {
            // Deliberately unclamped here. Pokemon.setCurrentHealth already clamps into
            // [0, maxHealth] against the real Pokemon, pushes a HealthUpdatePacket to the client,
            // and -- when the value lands on zero -- decrements friendship, arms
            // CobblemonConfig.defaultFaintTimer and fires POKEMON_FAINTED. So a fainted raid
            // Pokemon gets Cobblemon's ordinary faint handling, including its revival timer,
            // without this class implementing any of it.
            original.setCurrentHealth(clone.getCurrentHealth());
        }

        if (carryover.status()) {
            original.setStatus(clone.getStatus());
        }

        if (carryover.pp()) {
            copyPp(clone.getMoveSet(), original.getMoveSet());
        }

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            LOGGER.info("[CobbleRaids] Raid cost: {} hp {}/{}{}", original.getSpecies().getName(),
                    original.getCurrentHealth(), original.getMaxHealth(),
                    original.isFainted() ? " (fainted)" : "");
        }
    }

    /**
     * Copied slot by slot rather than by move name. The clone's move set is a copy of the party
     * Pokemon's, so the slots line up; matching on name instead would mishandle a Pokemon carrying
     * the same move twice. getMovesWithNulls keeps empty slots aligned with their real indices.
     */
    private static void copyPp(MoveSet from, MoveSet to) {
        List<Move> source = from.getMovesWithNulls();
        List<Move> target = to.getMovesWithNulls();
        int slots = Math.min(source.size(), target.size());
        for (int i = 0; i < slots; i++) {
            Move used = source.get(i);
            Move real = target.get(i);
            if (used == null || real == null) continue;
            // A clone whose move set was rebuilt could report a different maximum; never hand the
            // real move more PP than it can hold, and never a negative.
            real.setCurrentPp(Math.max(0, Math.min(used.getCurrentPp(), real.getMaxPp())));
        }
    }
}
