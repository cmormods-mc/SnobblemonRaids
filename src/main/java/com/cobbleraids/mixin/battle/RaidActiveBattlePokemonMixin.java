package com.cobbleraids.mixin.battle;

import com.cobbleraids.raid.RaidRegistry;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.Targetable;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raid field geometry.
 *
 * Cobblemon positions active Pokemon on a line and treats two as adjacent when their positions are
 * within one step of each other, mirroring a foe's position across the field
 * ({@code foePos = pokemonPerSide - foeDigit + 1}). That assumes two symmetric sides. A raid is one
 * boss opposite up to four separately-actored players, so both the "opposite slot" and "adjacent"
 * questions have answers Cobblemon's model cannot express. Both are answered here for raid battles
 * only; every ordinary battle falls through to Cobblemon's own behavior untouched.
 */
@Mixin(ActiveBattlePokemon.class)
public abstract class RaidActiveBattlePokemonMixin {
    /**
     * Stock opposite-slot lookup assumes a conventional mirrored field, and a raid may have no
     * geometrically mirrored slot at all. Resolve any live non-allied active Pokemon instead:
     * players resolve the boss, the boss resolves one active player.
     */
    @Inject(method = "getOppositeOpponent", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidOppositeOpponent(CallbackInfoReturnable<Targetable> cir) {
        ActiveBattlePokemon self = (ActiveBattlePokemon) (Object) this;
        if (!RaidRegistry.contains(self.getBattle())) return;

        for (ActiveBattlePokemon candidate : self.getAllActivePokemon()) {
            if (candidate == self || !candidate.hasPokemon() || candidate.isGone()) continue;
            if (!self.isAllied(candidate)) {
                cir.setReturnValue(candidate);
                return;
            }
        }
    }

    /**
     * In a raid every active Pokemon is adjacent to every other, which is the geometry the Showdown
     * half already assumes -- raid-patch.js points every player side's foe at the boss.
     *
     * Without this, the mirrored-position model left only the players nearest the boss's mirrored
     * slot able to target it. Measured in a live 4-player raid: p1a/p2b could target the boss at
     * p5a while p3c/p4d could only target each other, so those two players had every move refused
     * with "Invalid action choice", the server re-prompted them forever, and the raid hung waiting
     * on a choice they were not allowed to make. No field width fixes it either -- the boss mirrors
     * onto a single position, and players at both ends of the line cannot both be within one step
     * of it -- so raid adjacency is replaced rather than tuned.
     *
     * Cobblemon derives the rest from this list: getAdjacentOpponents() filters it to the boss for
     * a player and to the players for the boss, and getAdjacentAllies() filters it to the other
     * players, so ally-targeting moves keep working. MoveActionResponse.isValid() validates a
     * submitted target against the same list, which is what unblocks the rejected choices.
     *
     * Runs only while resolving or validating a move choice, never on the battle tick, and walks at
     * most the boss plus the raid's players -- a handful of comparisons per submitted action.
     */
    @Inject(method = "getAdjacent", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidAdjacency(CallbackInfoReturnable<List<Targetable>> cir) {
        ActiveBattlePokemon self = (ActiveBattlePokemon) (Object) this;
        if (!RaidRegistry.contains(self.getBattle())) return;

        List<Targetable> adjacent = new ArrayList<>();
        for (ActiveBattlePokemon candidate : self.getAllActivePokemon()) {
            // isGone() covers a fainted or withdrawn slot and hasPokemon() an empty one; either would
            // otherwise be offered as a target that cannot legally be hit.
            if (candidate == self || !candidate.hasPokemon() || candidate.isGone()) continue;
            adjacent.add(candidate);
        }
        cir.setReturnValue(adjacent);
    }
}
