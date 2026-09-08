package com.cobbleraids.lifecycle;

import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.raid.RaidSession;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.experience.BattleExperienceSource;
import com.cobblemon.mod.common.api.pokemon.stats.BattleEvSource;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Awards a won raid's experience and EVs to the participants' real Pokemon.
 *
 * <h2>Why this has to exist</h2>
 * A raid is fought on copies. {@code RaidFactory} builds each team with
 * {@code toBattleTeam(clone = true)}, so {@code BattlePokemon.safeCopyOf} sets
 * {@code originalPokemon} to the party Pokemon and {@code effectedPokemon} to a throwaway
 * {@code clone()}. That isolation is deliberate and load-bearing: every instruction that writes
 * battle state -- Damage, Heal, Switch, Faint -- writes only to {@code effectedPokemon}, which is
 * why a raid costs a player nothing. Lost HP and faints never reach the party, and should not.
 *
 * <p>Progression was lost with it. Cobblemon awards experience in {@code PokemonBattle.end()}, and
 * {@code PlayerBattleActor.awardExperience} adds it to {@code getEffectedPokemon()} -- the clone --
 * in both of its branches. {@code originalPokemon} is final and nothing copies clone state back, so
 * a raid produced no experience, no EVs and no evolution progress at all.
 *
 * <h2>Why it computes instead of copying the clone</h2>
 * Copying what Cobblemon put on the clone was tried first and does not work, because
 * <em>Cobblemon awards nothing in a raid to begin with</em>. That was measured, not assumed: with a
 * level-5 boss at {@code health=0}, a level-5 receiver that survived at {@code health=5} and
 * carried the boss in its {@code facedOpponents}, {@code isPvP=false} and no level cap in play --
 * every precondition of its award loop satisfied -- the clone's experience was still unchanged
 * after {@code end()} had run. A raid's one-shared-side, boss-on-side-2 shape does not drive that
 * loop, so there is nothing on the clone to copy.
 *
 * <p>The amounts are therefore computed here with Cobblemon's own configured calculators, against
 * the boss's BattlePokemon as the defeated opponent. {@code StandardExperienceCalculator} reads
 * {@code getOriginalPokemon().getLevel()} on both sides, so the real party Pokemon's level and the
 * real boss's level drive the result exactly as they would in an ordinary battle -- including
 * giving a level-capped Pokemon nothing.
 *
 * <p>Only experience and EVs are granted. HP, status, PP and faints stay on the clone on purpose:
 * carrying those over would turn raids into a cost, which is a balance decision and not this
 * class's to make.
 */
public final class RaidProgressionTransfer {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleRaids");

    /**
     * No experience-share modelling: a raid grants to the Pokemon that actually fought the boss, at
     * the full rate. Cobblemon's {@code experienceShareMultiplier} covers held-item shares in
     * ordinary battles, a path a raid never reaches.
     */
    private static final double MULTIPLIER = 1.0;

    private RaidProgressionTransfer() {}

    /** Call only on a won raid, and only once Cobblemon has ended the battle. */
    public static void grant(RaidSession raid, MinecraftServer server) {
        if (raid == null || server == null) return;
        PokemonBattle battle = raid.getBattle();

        BattlePokemon boss = bossPokemon(battle, raid);
        if (boss == null) {
            LOGGER.warn("[CobbleRaids] Raid ended with no resolvable boss Pokemon; no progression granted.");
            return;
        }

        for (BattleActor actor : battle.getActors()) {
            if (!(actor instanceof PlayerBattleActor)) continue;

            UUID playerId = actor.getUuid();
            // Withdrawing or disconnecting forfeits the raid's rewards, so it forfeits progression
            // too; RaidRewardEligibility draws the same line for items.
            if (!raid.isActiveParticipant(playerId)) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            for (BattlePokemon battlePokemon : actor.getPokemonList()) {
                try {
                    grantTo(battle, battlePokemon, boss, player);
                } catch (RuntimeException ex) {
                    // One Pokemon failing must not cost the rest of the party its experience, and
                    // must never stop the raid finalizing -- the reward screen is queued after this.
                    LOGGER.error("[CobbleRaids] Failed to grant raid progression for {}",
                            battlePokemon.getOriginalPokemon().getSpecies().getName(), ex);
                }
            }
        }
    }

    private static void grantTo(PokemonBattle battle, BattlePokemon receiver, BattlePokemon boss,
                                ServerPlayer player) {
        // Mirrors what an ordinary battle rewards: the Pokemon has to have actually been in against
        // the boss, and has to have come out of it standing. A Pokemon that fainted is only fainted
        // on its clone, so withholding the experience costs the player nothing else.
        if (receiver == boss) return;
        if (!receiver.getFacedOpponents().contains(boss)) return;
        if (receiver.getHealth() <= 0) return;

        Pokemon original = receiver.getOriginalPokemon();
        List<BattlePokemon> faced = List.of(boss);

        int experience = Cobblemon.INSTANCE.getExperienceCalculator().calculate(receiver, boss, MULTIPLIER);
        if (experience > 0) {
            BattleExperienceSource source = new BattleExperienceSource(battle, faced);
            // With a player online, Cobblemon reports the level-up and offers any evolution it
            // unlocked; without one the experience still lands silently.
            if (player != null) {
                original.addExperienceWithPlayer(player, source, experience);
            } else {
                original.addExperience(source, experience);
            }
        }

        Map<Stat, Integer> evs = Cobblemon.INSTANCE.getEvYieldCalculator().calculate(receiver, boss);
        for (Map.Entry<Stat, Integer> entry : evs.entrySet()) {
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (amount <= 0) continue;
            // EVs.add enforces the per-stat and total caps itself.
            original.getEvs().add(entry.getKey(), amount, new BattleEvSource(battle, faced, original));
        }

        if (CobbleRaidsConfigManager.get().debugLogging()) {
            LOGGER.info("[CobbleRaids] Raid progression: {} (lv {}) +{} exp, evs {}",
                    original.getSpecies().getName(), original.getLevel(), experience, evs);
        }
    }

    /** The boss is one Pokemon on its own actor; RaidFactory guarantees exactly one. */
    private static BattlePokemon bossPokemon(PokemonBattle battle, RaidSession raid) {
        BattleActor bossActor = battle.getActor(raid.getBossActorId());
        if (bossActor == null) return null;
        List<BattlePokemon> list = bossActor.getPokemonList();
        return list.isEmpty() ? null : list.get(0);
    }
}
