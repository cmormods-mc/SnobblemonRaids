package com.cobbleraids.pokemon;

import com.cobblemon.mod.common.pokemon.Pokemon;

/**
 * The one invariant every raid-boss leveling path shares: a level change moves max HP, and a boss
 * left on its old current health would enter the fight already damaged. Was duplicated between
 * {@code RaidLobbyManager}'s dynamic-level scaling and {@code EncounterService}'s owned-encounter
 * level, each independently reimplementing the same three lines.
 */
public final class PokemonLeveling {
    private PokemonLeveling() {}

    /**
     * Sets {@code pokemon} to {@code level} and, only if that actually changes anything, resets its
     * current health to the new max. Returns whether the level changed, so a caller that only wants
     * to rebuild a nameplate or log a line when something actually happened can ask.
     */
    public static boolean applyLevel(Pokemon pokemon, int level) {
        if (pokemon.getLevel() == level) return false;
        pokemon.setLevel(level);
        pokemon.setCurrentHealth(pokemon.getMaxHealth());
        return true;
    }
}
